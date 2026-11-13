package cord.kasane.music

import android.content.Context
import cord.kasane.music.provider.sabr.SabrWire
import java.net.ServerSocket
import java.io.InputStream
import java.util.Collections
import kotlin.concurrent.thread

/** Locally generated sine-wave fMP4, wrapped in actual UMP framing. No YouTube audio assets. */
internal class SabrFixtureServer(private val context: Context) : AutoCloseable {
    private val server = ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"))
    val endpoint = "http://127.0.0.1:${server.localPort}/sabr"
    val segments = Collections.synchronizedList(mutableListOf<Int>())
    @Volatile var rejectProtection = false
    init { thread(isDaemon=true) {
        while(!server.isClosed) {
            val socket=try { server.accept() } catch(_:Exception) { break }
            socket.use {
                try {
                    it.soTimeout=10000
                    val input=it.getInputStream()
                    val path=line(input).split(' ').getOrElse(1){""}.substringBefore('?')
                    var size=0; var headers=0
                    while(true) {
                        val value=line(input)
                        if(value.isEmpty()) break
                        check(++headers<128)
                        if(value.startsWith("Content-Length:",true)) size=value.substringAfter(':').trim().toInt()
                        check(!value.startsWith("Cookie:",true) && !value.startsWith("Authorization:",true))
                    }
                    check(size in 0..200000)
                    val body=ByteArray(size);var read=0
                    while(read<size) { val n=input.read(body,read,size-read);check(n>0);read+=n }
                    val bytes=if(path=="/sabr") {
                        val request=SabrWire.decode(body)
                        val number=(request.number(4)/6400).toInt()+1
                        check(number in 1..13);segments.add(number)
                        if(rejectProtection && number>1) part(58,SabrWire.number(1,3))
                        else response(number,request.bytes[2].isNullOrEmpty())
                    } else context.assets.open("hls/"+path.substringAfterLast('/')).use { asset->asset.readBytes() }
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()
                    }
                } catch(_:Exception) { /* A canceled loader may close its socket. */ }
            }
        }
    } }
    private fun line(input:InputStream):String {
        val output=java.io.ByteArrayOutputStream()
        while(true) { val b=input.read();check(b>=0);if(b==10) break;check(output.size()<8192);if(b!=13)output.write(b) }
        return output.toString("UTF-8")
    }
    private fun uint(n:Int):ByteArray=if(n<128) byteArrayOf(n.toByte()) else byteArrayOf(0xf0.toByte(),n.toByte(),(n ushr 8).toByte(),(n ushr 16).toByte(),(n ushr 24).toByte())
    private fun part(type:Int,b:ByteArray)=uint(type)+uint(b.size)+b
    private fun response(number:Int,init:Boolean):ByteArray {
        var result=part(58,SabrWire.number(1,1))
        if(init) {
            result+=part(42,SabrWire.text(1,"fixture0001")+SabrWire.bytes(2,SabrWire.number(1,140))+
                SabrWire.number(9,3687424)+SabrWire.number(10,48000)+SabrWire.number(4,13))
            result+=media(0,number,true,context.assets.open("sabr/init.mp4").use { it.readBytes() })
        }
        return result+media(1,number,false,context.assets.open("sabr/segment%02d.m4s".format(number-1)).use { it.readBytes() })
    }
    private fun media(key:Int,number:Int,init:Boolean,data:ByteArray):ByteArray {
        val ticks=(number-1L)*307200
        val header=SabrWire.number(1,key.toLong())+SabrWire.text(2,"fixture0001")+SabrWire.number(3,140)+
            SabrWire.number(8,if(init)1 else 0)+SabrWire.number(9,number.toLong())+SabrWire.number(14,data.size.toLong())+
            SabrWire.bytes(15,SabrWire.number(1,ticks)+SabrWire.number(2,if(number==13)1024 else 307200)+SabrWire.number(3,48000))
        return part(20,header)+part(21,byteArrayOf(key.toByte())+data)+part(22,byteArrayOf(key.toByte()))
    }
    override fun close() { server.close() }
}
