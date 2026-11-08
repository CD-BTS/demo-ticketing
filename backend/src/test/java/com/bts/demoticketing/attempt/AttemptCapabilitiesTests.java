package com.bts.demoticketing.attempt;

import org.junit.jupiter.api.Test;
import com.bts.demoticketing.user.service.DemoAuthService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptCapabilitiesTests {
    @Test void exposesExplicitFeatureStateWithoutDatabaseAccess(){
        var service=mock(AttemptService.class);
        var controller=new AttemptController(service,mock(DemoAuthService.class));
        when(service.enabled()).thenReturn(false);
        assertFalse(controller.capabilities().data().enabled());
        when(service.enabled()).thenReturn(true);
        assertTrue(controller.capabilities().data().enabled());
        verify(service,times(2)).enabled();
        verifyNoMoreInteractions(service);
    }
}
