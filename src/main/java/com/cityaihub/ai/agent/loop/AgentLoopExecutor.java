package com.cityaihub.ai.agent.loop;

import com.cityaihub.ai.agent.scene.AiAgentScene;
import com.cityaihub.component.AgentLoopObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class AgentLoopExecutor {

    private final ManualToolAgentLoopExecutor manualToolAgentLoopExecutor;

    public AgentLoopResult execute(AgentLoopRequest request) {
        return execute(request, AgentLoopObserver.noop());
    }

    public AgentLoopResult execute(AgentLoopRequest request, AgentLoopObserver observer) {
        AgentLoopResult result = new AgentLoopResult();
        result.setScene(AiAgentScene.MULTI_AGENT.name());
        result.setToolLogs(new ArrayList<>());
        result.setAgentLoopExecution(manualToolAgentLoopExecutor.execute(request, result.getToolLogs(), observer));
        return result;
    }
}
