package com.bts.demoticketing.attempt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttemptRulesTests {
    @Test void clientsCannotInventBusinessSuccessOrSkipAdmission(){
        assertFalse(AttemptRules.clientAllowed("STARTED","ENTERED",false,"COMPLETED","SUCCEEDED"));
        assertFalse(AttemptRules.clientAllowed("QUEUE","ENTERED",false,"SEAT_SELECTION","ENTERED"));
        assertTrue(AttemptRules.clientAllowed("QUEUE","SUCCEEDED",false,"SEAT_SELECTION","ENTERED"));
        assertFalse(AttemptRules.clientAllowed("SEAT_SELECTION","ENTERED",false,"PAYMENT","ENTERED"));
        assertTrue(AttemptRules.clientAllowed("SEAT_HOLD","SUCCEEDED",true,"PAYMENT","ENTERED"));
    }
    @Test void explicitAbandonmentIsDistinctFromUnobservedFailure(){
        assertTrue(AttemptRules.clientAllowed("QUEUE","ENTERED",false,"QUEUE","ABANDONED"));
        assertFalse(AttemptRules.clientAllowed("QUEUE","ENTERED",false,"QUEUE","FAILED"));
        assertFalse(AttemptRules.clientAllowed("COMPLETED","SUCCEEDED",true,"PAYMENT","ABANDONED"));
        assertFalse(AttemptRules.clientAllowed("SEAT_HOLD","EXPIRED",true,"PAYMENT","ENTERED"));
    }
}
