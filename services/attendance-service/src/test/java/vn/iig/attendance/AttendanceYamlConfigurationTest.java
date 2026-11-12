package vn.iig.attendance;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring's YAML binder refuses duplicate mapping keys. A duplicate
 * iig.attendance group previously passed unit tests but crashed the Docker
 * service at startup and surfaced as a misleading Gateway HTTP 500.
 */
class AttendanceYamlConfigurationTest {
    @Test void startupConfigurationHasOneUnambiguousAttendanceSection()
            throws Exception {
        LoaderOptions options=new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try(InputStream input=getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(input,"Attendance application.yml must be packaged");
            Map<?,?> root=new Yaml(new SafeConstructor(options)).load(input);
            assertNotNull(root);
            Map<?,?> iig=(Map<?,?>)root.get("iig");
            assertNotNull(iig);
            Map<?,?> attendance=(Map<?,?>)iig.get("attendance");
            assertNotNull(attendance);
            assertEquals("${ATTENDANCE_COMMAND_RECOVERY_ENABLED:false}",
                attendance.get("command-recovery-enabled"));
            assertEquals("${ATTENDANCE_WRITE_GATEWAY_ENABLED:false}",
                attendance.get("write-gateway-enabled"));
            assertEquals("${ATTENDANCE_SOURCE_RELAY_ENABLED:false}",
                attendance.get("source-relay-enabled"));
        }
    }
}
