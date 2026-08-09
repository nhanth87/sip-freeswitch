package et.elisa.fsxml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Directory XML generation is Panache-backed; this test locks the helper userXml shape
 * via reflection-free public POST path only when DB available — here we assert esc/notFound
 * contract by invoking private static through package test of notFound content.
 */
class FsXmlCurlDirectoryContractTest {

    @Test
    void notFoundDocumentShape() throws Exception {
        var m = FsXmlCurlResource.class.getDeclaredMethod("notFound", String.class);
        m.setAccessible(true);
        String xml = (String) m.invoke(null, "directory");
        assertTrue(xml.contains("status=\"not found\""));
        assertTrue(xml.contains("directory"));
    }

    @Test
    void userXmlUsesA1HashWhenLooksLikeHash() throws Exception {
        var m = FsXmlCurlResource.class.getDeclaredMethod("userXml", String.class, String.class, String.class);
        m.setAccessible(true);
        // 32 hex chars — SipDigestHasher.looksLikeA1Hash
        String xml = (String) m.invoke(null, "2519", "00112233445566778899aabbccddeeff", "2519");
        assertTrue(xml.contains("a1-hash"), xml);
        assertTrue(xml.contains("id=\"2519\""));
    }
}
