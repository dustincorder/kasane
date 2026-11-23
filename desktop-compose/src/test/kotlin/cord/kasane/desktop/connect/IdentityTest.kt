package cord.kasane.desktop.connect

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.interfaces.ECPublicKey
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityTest {
    @Test
    fun identityIsP256AndStableAcrossRestarts() {
        val dir = createTempDirectory("kasane-identity").toFile()
        val first = loadIdentity(dir, "qube")
        val key = first.certificate.publicKey as ECPublicKey
        assertEquals(256, key.params.curve.field.fieldSize)
        assertEquals("SHA256withECDSA", first.certificate.sigAlgName)
        val second = loadIdentity(dir, "other name")
        assertEquals(first.deviceId, second.deviceId)
        assertEquals("qube", second.deviceName)
        assertEquals(first.fingerprintHex, second.fingerprintHex)
        val perms = Files.getPosixFilePermissions(dir.resolve("identity.pass").toPath())
        assertTrue(perms == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), "password file must be owner-only: $perms")
        dir.deleteRecursively()
    }
}
