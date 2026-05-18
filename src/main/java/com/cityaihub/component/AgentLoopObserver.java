package com.cityaihub.component;

import com.cityaihub.ai.agent.loop.AgentLoopTrace;
import com.cityaihub.ai.agent.loop.ToolExecutionLog;

public interface AgentLoopObserver {

    AgentLoopObserver NOOP = new AgentLoopObserver() {
    };

    default void onTrace(AgentLoopTrace trace) {
    }

    default void onToolExecuted(ToolExecutionLog log) {
    }

    static AgentLoopObserver noop() {
        return NOOP;
    }
}
