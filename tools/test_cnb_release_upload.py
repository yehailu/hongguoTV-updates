"""Offline, credential-free tests for the CNB release publisher."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import urllib.error
import zipfile

UPLOADER = Path(__file__).with_name("cnb_release_upload.py")
SPEC = importlib.util.spec_from_file_location("cnb_release_upload_under_test", UPLOADER)
pub = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = pub
SPEC.loader.exec_module(pub)
SHA = "a" * 40
FAKE_SECRET = "dummy-secret-not-a-real-token"


class Response:
    def __init__(self, status=200, payload=b"{}", headers=None):
        self.status, self.payload, self.headers = status, payload, headers or {}
    def __enter__(self):
        return self
    def __exit__(self, *_):
        return False
    def read(self, limit=-1):
        return self.payload[:limit] if limit >= 0 else self.payload


class FakeClient:
    """No network: models the upload protocol and records intended API writes."""
    def __init__(self):
        self.calls, self.puts = [], []
        self.existing = None
        self.fail_asset = None
        self.verify_status = 200
        self.verify_url = None
        self.create_status = 201
        self.patch_status = 200
        self.latest_id = "r1"
    def call(self, method, url, body=None):
        self.calls.append((method, url, body))
        if method == "GET" and "/tags/" in url:
            return (200, json.dumps(self.existing).encode()) if self.existing else (404, b"not found")
        if method == "POST" and url.endswith("/-/releases"):
            return self.create_status, json.dumps({"id":"r1", "prerelease":True, "target_commitish":body["target_commitish"], "assets":[], "echo":FAKE_SECRET}).encode()
        if url.endswith("asset-upload-url"):
            return 201, json.dumps({"upload_url":"https://bucket.example/upload?signature=opaque", "verify_url":self.verify_url or f"{pub.API}/{pub.REPO}/-/releases/r1/assets/{body['asset_name']}/verify"}).encode()
        if url.endswith("/verify"):
            return self.verify_status, (b"{}" if self.verify_status == 200 else FAKE_SECRET.encode())
        if method == "PATCH":
            return self.patch_status, (b'{"prerelease":false}' if self.patch_status == 200 else FAKE_SECRET.encode())
        if method == "GET" and url.endswith("/latest"):
            return 200, json.dumps({"id":self.latest_id,"prerelease":False}).encode()
        raise AssertionError("Unexpected fake protocol operation")
    def put(self, url, asset):
        self.puts.append((url, asset.name))
        if asset.name == self.fail_asset:
            raise pub.ReleaseError("upload failed (HTTP 503)")


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.apk = self.folder / "hongguotv-0.27.0-sw-tv-debug.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture manifest")
            archive.writestr("classes.dex", b"fixture dex")
        self.doc = {"versionName":"0.27.0", "versionCode":53, "variants":{"sw-tv":{
            "versionName":"0.27.0", "versionCode":53, "file":self.apk.name,
            "size":self.apk.stat().st_size, "sha256":pub._hash(self.apk), "minSdk":27}}}
        self.write_metadata()
    def write_metadata(self):
        (self.folder / "update.json").write_text(json.dumps(self.doc), encoding="utf-8")
    def reader(self, _):
        return {"packageName":pub.PACKAGE, "versionName":"0.27.0", "versionCode":53, "minSdk":27}
    def invoke(self, client=None, extra=(), reader=None):
        out, err = io.StringIO(), io.StringIO()
        argv = ["--tag","v0.27.0","--dir",str(self.folder),"--target-commit",SHA,*extra]
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            result = pub.main(argv, client=client, metadata_reader=reader or self.reader)
        return result, out.getvalue(), err.getvalue()

    def test_import_without_credentials_does_not_read_or_resolve_any(self):
        spec = importlib.util.spec_from_file_location("fresh_import_without_credentials", UPLOADER)
        module = importlib.util.module_from_spec(spec)
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(Path, "read_text", side_effect=AssertionError("credential read at import")), mock.patch.dict(sys.modules, {spec.name:module}):
            spec.loader.exec_module(module)
        self.assertFalse(hasattr(module, "PAT"))

    def test_process_credentials_take_priority_without_reading_registry_or_file(self):
        reader = mock.Mock(side_effect=AssertionError("registry read"))
        with mock.patch.object(Path, "read_text", side_effect=AssertionError("file read")):
            self.assertEqual("one", pub.token({"CNB_API_TOKEN":"one", "API_TOKEN":"two", "CNB_TOKEN":"three"}, reader))
            self.assertEqual("two", pub.token({"API_TOKEN":"two", "CNB_TOKEN":"three"}, reader))
            self.assertEqual("three", pub.token({"CNB_TOKEN":"three"}, reader))
        reader.assert_not_called()

    def test_windows_user_credential_precedes_ignored_legacy_fallback(self):
        with mock.patch.object(Path, "read_text", side_effect=AssertionError("file read")):
            self.assertEqual(FAKE_SECRET, pub.token({}, lambda:FAKE_SECRET))
        with mock.patch.object(Path, "read_text", return_value="https://owner:legacy%2Bdummy@cnb.cool/repo"):
            self.assertEqual("legacy+dummy", pub.token({}, lambda:None))

    def test_missing_or_invalid_credentials_fail_without_echoing_values(self):
        with mock.patch.object(Path, "read_text", side_effect=FileNotFoundError):
            with self.assertRaises(pub.ReleaseError):
                pub.token({}, lambda:None)
        with self.assertRaises(pub.ReleaseError) as result:
            pub.token({"CNB_API_TOKEN":FAKE_SECRET+"\nAuthorization: leaked"}, lambda:None)
        self.assertNotIn(FAKE_SECRET, str(result.exception))

    def test_windows_registry_reads_only_authorized_user_variable(self):
        registry = SimpleNamespace(HKEY_CURRENT_USER=1, KEY_READ=2, OpenKey=mock.MagicMock(), QueryValueEx=mock.Mock(return_value=(FAKE_SECRET,1)))
        with mock.patch.object(pub.os, "name", "nt"), mock.patch.dict(sys.modules, {"winreg":registry}):
            self.assertEqual(FAKE_SECRET, pub._windows_user_token())
        registry.OpenKey.assert_called_once_with(1, "Environment", 0, 2)
        registry.QueryValueEx.assert_called_once_with(registry.OpenKey.return_value.__enter__.return_value, "CNB_API_TOKEN")

    def test_prerelease_no_latest_never_patches_or_reads_latest(self):
        client = FakeClient()
        status, out, err = self.invoke(client, ("--prerelease","--no-latest"))
        self.assertEqual(0, status, err)
        create = [body for method,url,body in client.calls if method == "POST" and url.endswith("/-/releases")][0]
        self.assertTrue(create["prerelease"])
        self.assertEqual("false", create["make_latest"])
        self.assertEqual(SHA, create["target_commitish"])
        self.assertFalse(any(method == "PATCH" or url.endswith("/latest") for method,url,_ in client.calls))
        self.assertTrue(all(body["overwrite"] is False for _,url,body in client.calls if url.endswith("asset-upload-url")))
        self.assertNotIn(FAKE_SECRET, out+err)
        self.assertTrue(client.puts[-1][1] == "update.json")

    def test_no_latest_alone_also_creates_prerelease(self):
        client = FakeClient()
        result, _, error = self.invoke(client, ("--no-latest",))
        self.assertEqual(0, result, error)
        self.assertFalse(any(method == "PATCH" for method,_,_ in client.calls))

    def test_partial_upload_failure_never_promotes_and_exits_nonzero(self):
        client = FakeClient(); client.fail_asset = "update.json"
        result, out, error = self.invoke(client)
        self.assertEqual(1, result)
        self.assertEqual(2, len(client.puts))
        self.assertFalse(any(method == "PATCH" for method,_,_ in client.calls))
        self.assertNotIn(FAKE_SECRET, out+error)

    def test_verify_http_failure_stops_before_promotion(self):
        client = FakeClient(); client.verify_status = 503
        result, out, error = self.invoke(client)
        self.assertEqual(1, result)
        self.assertFalse(any(method == "PATCH" for method,_,_ in client.calls))
        self.assertEqual(1, len(client.puts))
        self.assertNotIn(FAKE_SECRET, out+error)

    def test_promotion_http_failure_is_not_reported_as_success(self):
        client = FakeClient(); client.patch_status = 500
        result, out, error = self.invoke(client)
        self.assertEqual(1, result)
        self.assertNotIn("latest confirmed", out)
        self.assertNotIn(FAKE_SECRET, out+error)
        self.assertFalse(any(url.endswith("/latest") for _,url,_ in client.calls))

    def test_stable_promotion_confirms_latest_identifier(self):
        client = FakeClient()
        self.assertEqual(0, self.invoke(client)[0])
        self.assertTrue(any(method == "PATCH" for method,_,_ in client.calls))
        client = FakeClient(); client.latest_id = "another-release"
        self.assertEqual(1, self.invoke(client)[0])

    def test_creation_status_error_is_nonzero_and_credentials_are_not_echoed(self):
        client = FakeClient(); client.create_status = 403
        status, out, err = self.invoke(client)
        self.assertEqual(1, status)
        self.assertFalse(client.puts)
        self.assertNotIn(FAKE_SECRET, out+err)

    def test_existing_stable_release_is_untouched_in_test_mode(self):
        client = FakeClient(); client.existing = {"id":"r1", "prerelease":False, "target_commitish":SHA, "assets":[]}
        self.assertEqual(1, self.invoke(client, ("--prerelease","--no-latest"))[0])
        self.assertEqual(1, len(client.calls)); self.assertFalse(client.puts)

    def test_existing_assets_need_explicit_overwrite(self):
        client = FakeClient(); client.existing = {"id":"r1", "prerelease":True, "target_commitish":SHA, "assets":[{"name":self.apk.name}]}
        self.assertEqual(1, self.invoke(client, ("--no-latest",))[0])
        self.assertFalse(client.puts)
        client.calls.clear()
        self.assertEqual(0, self.invoke(client, ("--no-latest","--overwrite"))[0])
        self.assertTrue(all(body["overwrite"] for _,url,body in client.calls if url.endswith("asset-upload-url")))

    def test_invalid_metadata_prevents_every_remote_operation(self):
        client = FakeClient(); self.doc["variants"]["sw-tv"]["sha256"] = "0"*64; self.write_metadata()
        self.assertEqual(1, self.invoke(client, ("--no-latest",))[0])
        self.assertEqual([], client.calls); self.assertEqual([], client.puts)

    def test_renamed_old_apk_cannot_be_claimed_as_new_release(self):
        client = FakeClient()
        old = lambda _: {"packageName":pub.PACKAGE,"versionName":"0.26.9","versionCode":52,"minSdk":27}
        result, _, err = self.invoke(client, ("--no-latest",), old)
        self.assertEqual(1, result); self.assertIn("rebuild instead of renaming", err)
        self.assertFalse(client.calls)

    def test_old_variant_metadata_cannot_hide_under_new_top_level_version(self):
        old_name = "hongguotv-0.26.9-sw-tv-debug.apk"
        self.apk.rename(self.folder / old_name)
        self.doc["variants"]["sw-tv"].update(file=old_name, versionName="0.26.9", versionCode=52)
        self.write_metadata()
        client = FakeClient()
        self.assertEqual(1, self.invoke(client, ("--no-latest",))[0])
        self.assertFalse(client.calls)

    def test_checksum_file_and_duplicate_apk_descriptors_are_checked(self):
        (self.folder / "SHA256SUMS.txt").write_text("0"*64+"  "+self.apk.name+"\n",encoding="utf-8")
        with self.assertRaises(pub.ReleaseError):
            pub.preflight(self.folder,"v0.27.0",self.reader)
        (self.folder / "SHA256SUMS.txt").write_text(pub._hash(self.apk)+"  "+self.apk.name+"\n",encoding="utf-8")
        self.assertEqual(3, len(pub.preflight(self.folder,"v0.27.0",self.reader)))
        self.doc["variants"]["duplicate"] = dict(self.doc["variants"]["sw-tv"]); self.write_metadata()
        with self.assertRaises(pub.ReleaseError):
            pub.preflight(self.folder,"v0.27.0",self.reader)

    def test_dry_run_uses_no_credentials_or_network(self):
        client = FakeClient()
        status, _, error = self.invoke(client, ("--dry-run",))
        self.assertEqual(0, status, error); self.assertFalse(client.calls)

    def test_untrusted_verify_url_prevents_presigned_upload(self):
        client = FakeClient(); client.verify_url = "https://evil.example/verify"
        self.assertEqual(1, self.invoke(client, ("--no-latest",))[0])
        self.assertFalse(client.puts)
        self.assertFalse(any(url.endswith("/verify") for _,url,_ in client.calls))

    def test_local_asset_changed_after_preflight_is_rejected(self):
        asset = pub.preflight(self.folder,"v0.27.0",self.reader)[0]
        asset.path.write_bytes(b"changed")
        client = FakeClient()
        with self.assertRaises(pub.ReleaseError):
            pub.upload(client,"r1",asset)
        self.assertFalse(client.calls)

    def test_api_auth_never_follows_cross_host_or_cleartext_redirects(self):
        for destination in ("https://evil.example/path", "http://api.cnb.cool/path"):
            with self.subTest(destination=destination):
                error = urllib.error.HTTPError(pub.API,302,"redirect",{"Location":destination},io.BytesIO(FAKE_SECRET.encode()))
                opener = SimpleNamespace(open=mock.Mock(side_effect=error))
                client = pub.HttpClient(lambda:FAKE_SECRET,opener)
                with self.assertRaises(pub.ReleaseError) as result:
                    client.call("GET",f"{pub.API}/{pub.REPO}/-/releases/latest")
                self.assertEqual(1,opener.open.call_count)
                self.assertNotIn(FAKE_SECRET,str(result.exception))

    def test_presigned_request_has_no_bearer_and_no_redirect_forwarding(self):
        asset = pub.preflight(self.folder,"v0.27.0",self.reader)[0]
        provider = mock.Mock(side_effect=AssertionError("token lookup for object store"))
        opener = SimpleNamespace(open=mock.Mock(return_value=Response()))
        client = pub.HttpClient(provider,opener)
        client.put("https://bucket.example/upload?signature=dummy",asset)
        request = opener.open.call_args.args[0]
        self.assertFalse(any(k.lower()=="authorization" for k,_ in request.header_items()))
        self.assertEqual(str(asset.size),request.get_header("Content-length"))
        provider.assert_not_called()
        opener.open.side_effect = urllib.error.HTTPError("https://bucket.example",307,"redirect",{"Location":"https://elsewhere.example"},io.BytesIO())
        with self.assertRaises(pub.ReleaseError):
            client.put("https://bucket.example/upload",asset)
        self.assertEqual(2,opener.open.call_count)

    def test_aapt_verifies_real_manifest_fields_without_inheriting_token_env(self):
        output = "package: name='com.hongguotv.nativeapp' versionCode='53' versionName='0.27.0'\nsdkVersion:'27'\n"
        result = SimpleNamespace(returncode=0, stdout=output, stderr="")
        with mock.patch.dict(os.environ,{"CNB_API_TOKEN":FAKE_SECRET}), mock.patch.object(subprocess,"run",return_value=result) as run:
            self.assertEqual(self.reader(self.apk),pub.apk_metadata(self.apk,"aapt"))
        self.assertNotIn("CNB_API_TOKEN",run.call_args.kwargs["env"])
        self.assertFalse(run.call_args.kwargs.get("shell",False))

    def test_tag_target_and_asset_validation_rejects_injection(self):
        for tag in ("../stable","v0.27.0?latest=true","v0.27.0\n",""):
            with self.assertRaises(pub.ReleaseError): pub.validate_tag(tag)
        for name in ("../update.json","update.json\r\nsecret", ".cnb-credentials"):
            with self.assertRaises(pub.ReleaseError): pub.validate_asset_name(name)
        with self.assertRaises(pub.ReleaseError): pub.validate_target_commit("main; publish")
        provider = mock.Mock(side_effect=AssertionError("untrusted URL must not resolve token"))
        client = pub.HttpClient(provider,SimpleNamespace(open=mock.Mock()))
        for url in ("http://api.cnb.cool/path","https://api.cnb.cool.evil.example/path","https://owner:pass@api.cnb.cool/path",f"{pub.API}/{pub.REPO}/-/releases/../admin",f"{pub.API}/{pub.REPO}/-/releases\n/latest"):
            with self.assertRaises(pub.ReleaseError): client.call("GET",url)
        provider.assert_not_called()
    def test_missing_credentials_exit_before_any_network(self):
        def unavailable():
            with mock.patch.object(Path, "read_text", side_effect=FileNotFoundError):
                return pub.token({}, lambda:None)
        opener = SimpleNamespace(open=mock.Mock())
        result, _, _ = self.invoke(pub.HttpClient(unavailable,opener), ("--no-latest",))
        self.assertEqual(1,result); opener.open.assert_not_called()

    def test_safe_get_redirect_stays_on_the_api_host(self):
        first = urllib.error.HTTPError(pub.API,302,"redirect",{"Location":f"/{pub.REPO}/-/releases/latest"},io.BytesIO())
        opener = SimpleNamespace(open=mock.Mock(side_effect=[first,Response(200,b'{"id":"r1"}')]))
        client = pub.HttpClient(lambda:FAKE_SECRET,opener)
        self.assertEqual((200,b'{"id":"r1"}'),client.call("GET",f"{pub.API}/{pub.REPO}/-/releases/tags/v0.27.0"))
        self.assertEqual(2,opener.open.call_count)
        for args in opener.open.call_args_list:
            self.assertTrue(args.args[0].full_url.startswith(pub.API+"/"))
            self.assertEqual("Bearer "+FAKE_SECRET,args.args[0].get_header("Authorization"))

    def test_write_redirect_is_rejected_even_on_the_same_host(self):
        first = urllib.error.HTTPError(pub.API,307,"redirect",{"Location":f"/{pub.REPO}/-/releases/latest"},io.BytesIO())
        opener = SimpleNamespace(open=mock.Mock(side_effect=first))
        with self.assertRaises(pub.ReleaseError):
            pub.HttpClient(lambda:FAKE_SECRET,opener).call("POST",f"{pub.API}/{pub.REPO}/-/releases",{})
        self.assertEqual(1,opener.open.call_count)

    def test_malformed_http_transport_error_is_credential_safe(self):
        opener = SimpleNamespace(open=mock.Mock(side_effect=pub.http.client.InvalidURL(FAKE_SECRET)))
        with self.assertRaises(pub.ReleaseError) as failure:
            pub.HttpClient(lambda:FAKE_SECRET,opener).call("GET",f"{pub.API}/{pub.REPO}/-/releases/latest")
        self.assertNotIn(FAKE_SECRET,str(failure.exception))

    def test_success_status_with_explicit_verify_failure_still_blocks_promotion(self):
        client = FakeClient()
        original = client.call
        def respond(method,url,body=None):
            result = original(method,url,body)
            return (200,b'{"success":false}') if url.endswith("/verify") else result
        client.call = respond
        self.assertEqual(1,self.invoke(client)[0])
        self.assertFalse(any(method=="PATCH" for method,_,_ in client.calls))
    def test_cnb_creation_dto_accepts_tag_commitish_and_null_assets(self):
        client = FakeClient()
        original = client.call
        def real_shape(method,url,body=None):
            status,payload = original(method,url,body)
            if method == "POST" and url.endswith("/-/releases"):
                payload = json.dumps({"id":"r1", "prerelease":True, "tag_commitish":SHA, "assets":None}).encode()
            return status,payload
        client.call = real_shape
        result,_,error = self.invoke(client,("--prerelease","--no-latest"))
        self.assertEqual(0,result,error)
        self.assertEqual(2,len(client.puts))
        self.assertFalse(any(method=="PATCH" for method,_,_ in client.calls))

    def test_cnb_existing_tag_commitish_precedes_legacy_target(self):
        for assets in (None,[]):
            with self.subTest(assets=assets):
                client = FakeClient()
                client.existing = {"id":"r1", "prerelease":True, "tag_commitish":SHA, "target_commitish":"main", "assets":assets}
                result,_,error = self.invoke(client,("--no-latest",))
                self.assertEqual(0,result,error)
                self.assertEqual(2,len(client.puts))
                self.assertFalse(any(method=="PATCH" for method,_,_ in client.calls))

    def test_wrong_tag_commitish_is_rejected_for_existing_and_created_release(self):
        for created in (False,True):
            with self.subTest(created=created):
                client = FakeClient()
                wrong = {"id":"r1", "prerelease":True, "tag_commitish":"b"*40, "target_commitish":SHA, "assets":None}
                if created:
                    original = client.call
                    def real_shape(method,url,body=None):
                        status,payload = original(method,url,body)
                        if method=="POST" and url.endswith("/-/releases"):
                            payload=json.dumps(wrong).encode()
                        return status,payload
                    client.call=real_shape
                else:
                    client.existing=wrong
                result,_,_ = self.invoke(client,("--no-latest",))
                self.assertEqual(1,result)
                self.assertFalse(client.puts)
                self.assertFalse(any(method=="PATCH" for method,_,_ in client.calls))

    def test_only_null_assets_is_normalized_other_non_lists_fail_closed(self):
        for malformed in ({},"[]",False,0,12):
            with self.subTest(assets=malformed):
                client=FakeClient()
                client.existing={"id":"r1", "prerelease":True, "tag_commitish":SHA, "assets":malformed}
                result,_,error=self.invoke(client,("--no-latest",))
                self.assertEqual(1,result)
                self.assertIn("Invalid existing release asset list",error)
                self.assertFalse(client.puts)


if __name__ == "__main__":
    unittest.main()
