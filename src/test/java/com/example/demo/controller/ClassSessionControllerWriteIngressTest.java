package com.example.demo.controller;
import com.example.demo.service.ClassSessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClassSessionControllerWriteIngressTest {
    private final ClassSessionService core=mock(ClassSessionService.class);
    private final ClassSessionController controller=new ClassSessionController(core);
    @Test void disabledCoreWritePathsFailClosedBeforeBusinessService() {
        ReflectionTestUtils.setField(controller,"publicWriteEnabled",false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.replace("class-1",List.of(),null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.reschedule("class-1",null,null)).getStatusCode());
        verifyNoInteractions(core);
    }
    @Test void legacyMonolithMayContinueItsOwnWrites() {
        ReflectionTestUtils.setField(controller,"publicWriteEnabled",true);
        controller.replace("class-1",List.of(),null);
        verify(core).replace("class-1",List.of(),null);
    }
}
