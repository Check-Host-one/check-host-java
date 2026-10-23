// Live smoke test: mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
//                  java -cp "target/classes:$(cat cp.txt)" examples/Smoke.java
import com.fasterxml.jackson.databind.JsonNode;
import one.checkhost.CheckHost;

public class Smoke {
    public static void main(String[] args) throws Exception {
        CheckHost ch = new CheckHost();
        JsonNode m = ch.ping("example.com", 2, "Europe");
        if (!"finished".equals(m.path("status").asText())) throw new AssertionError(m);
        for (JsonNode r : m.path("results"))
            System.out.printf("%s, %s: avg %s ms%n", r.at("/probe/city").asText(), r.at("/probe/country").asText(), r.at("/result/stats/avg"));
        System.out.println("https://check-host.one/r/" + m.path("resultSlug").asText());
        if (ch.hostInfo("1.1.1.1").path("asn").asInt() != 13335) throw new AssertionError("hostinfo");
        try { ch.getMeasurement("nope"); throw new AssertionError("want 404"); }
        catch (CheckHost.CheckHostException e) { if (e.status != 404) throw e; }
    }
}
