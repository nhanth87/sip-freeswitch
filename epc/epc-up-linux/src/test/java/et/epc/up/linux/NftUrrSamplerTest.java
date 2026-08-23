package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fake ProcessRunner JSON fixtures: element attribution, argv, soft-fail. */
class NftUrrSamplerTest {

    private static final class FakeRunner implements ProcessRunner {
        final List<List<String>> argvs = new ArrayList<>();
        String stdout = "";
        RuntimeException failure;

        @Override
        public String run(String... argv) {
            argvs.add(List.of(argv));
            if (failure != null) {
                throw failure;
            }
            return stdout;
        }
    }

    private static UrrIpIndex index() throws Exception {
        UrrIpIndex index = new UrrIpIndex();
        index.register("1", InetAddress.getByName("10.64.0.2"));
        index.register("2", InetAddress.getByName("10.64.0.3"));
        return index;
    }

    @Test
    void mapsElementBytesToRegisteredUrrs() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.stdout = """
                {"nftables": [{"metainfo": {"version": "1.0.9"}}, {"set": {
                  "family": "inet", "name": "urr_bytes", "table": "epc",
                  "elem": [
                    {"element": "10.64.0.2", "bytes": 1234},
                    {"element": "10.64.0.3", "bytes": 77},
                    {"element": "10.64.0.9", "bytes": 5}
                  ]}}]}
                """;
        NftUrrSampler sampler = new NftUrrSampler(runner, "epc", "urr_bytes", index());

        Map<String, Long> sample = sampler.sample();

        assertEquals(Map.of("1", 1234L, "2", 77L), sample,
                "unregistered UE 10.64.0.9 is omitted");
        assertEquals(List.of("nft", "-j", "list", "set", "epc", "urr_bytes"),
                runner.argvs.get(0));
    }

    @Test
    void parsesNativeNftElemValShape() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.stdout = """
                {"nftables":[{"set":{"name":"urr_bytes","elem":[
                  {"elem":{"val":"10.64.0.2","counter":{"pkts":3,"bytes":456}}},
                  {"elem":{"val":"10.64.0.3","counter":{"pkts":1,"bytes":8}}}
                ]}}]}
                """;
        NftUrrSampler sampler = new NftUrrSampler(runner, "epc", "urr_bytes", index());

        assertEquals(Map.of("1", 456L, "2", 8L), sampler.sample());
    }

    @Test
    void runnerFailureSoftFailsToEmptySample() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.failure = new IllegalStateException("nft exit=1: No such file or directory");
        NftUrrSampler sampler = new NftUrrSampler(runner, "epc", "urr_bytes", index());

        assertTrue(sampler.sample().isEmpty());
    }

    @Test
    void emptyOutputYieldsEmptySample() throws Exception {
        FakeRunner runner = new FakeRunner();
        NftUrrSampler sampler = new NftUrrSampler(runner, "epc", "urr_bytes", index());

        assertTrue(sampler.sample().isEmpty());
    }

    @Test
    void defaultRunnerRequiresArgv() {
        assertThrows(IllegalArgumentException.class, () -> ProcessRunner.processBuilder().run());
    }
}
