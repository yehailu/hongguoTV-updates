// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class RemoteVideoPrefetchTest {
    private fun video(size: Int = 2*1024*1024, indexSize: Int = 8): ByteArray {
        val bytes=ByteArray(size) { (it%251).toByte() }
        ByteBuffer.wrap(bytes).putInt(8).put("ftyp".toByteArray()).putInt(indexSize).put("moov".toByteArray())
        return bytes
    }
    private fun client(bytes: ByteArray, calls: MutableList<String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request(); val range=request.header("Range")!!; calls+=range
        val parts=range.removePrefix("bytes=").split('-'); val first=parts[0].toInt(); val last=minOf(parts[1].toInt(),bytes.lastIndex)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
            .header("Content-Range","bytes $first-$last/${bytes.size}")
            .body(bytes.copyOfRange(first,last+1).toResponseBody()).build()
    }.build()
    @Test fun warmedPrefixIsReallyConsumedWithoutRepeatedNetworkReads() {
        val bytes=video(); val calls=mutableListOf<String>(); val http=client(bytes,calls)
        RemoteVideo(http,StreamInfo("https://example.invalid/video",null,"720P","avc")).use { remote ->
            remote.warm(); assertEquals(2,calls.size)
            assertArrayEquals(bytes.copyOfRange(0,64*1024),remote.read(0,64*1024))
            assertArrayEquals(bytes.copyOfRange(1024*1024-128,1024*1024),remote.read(1024*1024L-128,128))
            assertEquals(2,calls.size)
            assertArrayEquals(bytes.copyOfRange(1024*1024-64,1024*1024+64),remote.read(1024*1024L-64,128))
            assertEquals("bytes=1048576-1048639",calls.last())
        }
    }
    @Test fun smallFilesAreBoundedByTheirActualLength() {
        val bytes=video(1024); val calls=mutableListOf<String>()
        RemoteVideo(client(bytes,calls),StreamInfo("https://example.invalid/video",null,"480P")).use { remote ->
            remote.warm(); assertEquals(1,calls.size)
            assertArrayEquals(bytes,remote.read(0,4096))
            assertTrue(remote.read(1024,16).isEmpty()); assertEquals(1,calls.size)
        }
    }
    @Test fun oversizedSpeculativeIndexFallsBackToNormalPreparation() {
        val bytes=video(3*1024*1024,2*1024*1024); val calls=mutableListOf<String>()
        val remote=RemoteVideo(client(bytes,calls),StreamInfo("https://example.invalid/video",null,"720P"))
        assertThrows(IOException::class.java) { remote.warm() }
        assertEquals(1,calls.size)
        remote.prepare(); assertEquals(bytes.size.toLong(),remote.total)
        assertArrayEquals(bytes.copyOfRange(0,64),remote.read(0,64)); remote.close()
    }
    @Test fun closedVideoDoesNotStartFurtherPreparationOrRangeRequests() {
        val bytes=video(); val calls=mutableListOf<String>()
        val remote=RemoteVideo(client(bytes,calls),StreamInfo("https://example.invalid/video",null,"720P"))
        remote.close()
        assertThrows(IOException::class.java) { remote.prepare() }; assertTrue(calls.isEmpty())
    }
}
