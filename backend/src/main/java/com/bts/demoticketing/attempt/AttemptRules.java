package com.bts.demoticketing.attempt;

/** Client reports describe UI observations, never server business success. */
public final class AttemptRules {
    private AttemptRules(){}
    public static boolean clientAllowed(String currentStage,String currentStatus,boolean hasHold,String stage,String status){
        if(AttemptService.terminal(currentStage,currentStatus))return false;
        if("ABANDONED".equals(status))return currentStage.equals(stage);
        if(!"ENTERED".equals(status))return false;
        if("PAYMENT".equals(stage))return hasHold;
        if("SEAT_SELECTION".equals(stage))return (currentStage.equals("QUEUE")&&currentStatus.equals("SUCCEEDED"))
            ||currentStage.equals("SEAT_SELECTION");
        return false;
    }
}
