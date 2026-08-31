package top.colter.dynamic.koishi

import dbk.v1.Frame
import dbk.v1.FrameOp
import dbk.v1.HelloRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class DbkFrameTest {
    @Test
    fun `frame should round trip call payload`() {
        val request = HelloRequest(
            token = "secret",
            app_version = DBK_APP_VERSION,
            protocol_version = DBK_PROTOCOL_VERSION,
        )
        val encoded = Frame.ADAPTER.encode(
            Frame(
                op = FrameOp.FRAME_OP_CALL,
                id = "1",
                method = DbkMethods.SESSION_HELLO,
                payload = request.encodeByteString(),
            ),
        )

        val frame = Frame.ADAPTER.decode(encoded)
        assertEquals(FrameOp.FRAME_OP_CALL, frame.op)
        assertEquals("1", frame.id)
        assertEquals(DbkMethods.SESSION_HELLO, frame.method)
        assertEquals("secret", HelloRequest.ADAPTER.decode(frame.payload).token)
    }
}
