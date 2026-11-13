package com.bts.demoticketing.integration.bts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.user.entity.DemoUser;

/**
 * 마이페이지가 대기열과 같은 정책 출처를 보도록, 로그인 사용자에게 적용되는 정책을 조회한다.
 * 조회 전용: BTS 사용자 등록이나 정책 결정 이력을 만들지 않는다.
 */
@Service
public class AccountPolicyService {
    /** source: NONE(정책 미사용) / MOCK(로컬 설정) / BTS(원격). 모르는 값은 null. */
    public record View(String source,String action,Integer creditScore,String tier,Double anomalyScore){}

    private final String mode;
    private final PolicyDecisionClient policies;
    private final BtsClient bts;
    public AccountPolicyService(@Value("${demo.policy.mode:disabled}") String mode,PolicyDecisionClient policies,BtsClient bts){
        this.mode=mode;this.policies=policies;this.bts=bts;
    }

    public View view(DemoUser user){
        if(mode.equals("mock"))return new View("MOCK",policies.decide(user).name(),null,null,null);
        if(!mode.equals("bts"))return new View("NONE",null,null,null,null);
        // 아직 BTS에 등록되지 않은 사용자는 첫 대기열 진입 때 등록된다. 여기서는 만들지 않는다.
        if(user.btsUserId()==null)return new View("BTS",null,null,null,null);
        try{
            var account=bts.account(user.btsUserId());
            return new View("BTS",account.latestAction()==null?null:account.latestAction().name(),
                    account.creditScore(),account.tier(),account.anomalyScore());
        }catch(BtsClient.DeliveryFailure e){throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);}
    }
}
