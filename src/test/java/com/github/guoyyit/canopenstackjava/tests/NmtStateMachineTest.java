package com.github.guoyyit.canopenstackjava.tests;

import com.github.guoyyit.canopenstackjava.nmt.NmtCommand;
import com.github.guoyyit.canopenstackjava.nmt.NmtState;
import com.github.guoyyit.canopenstackjava.nmt.NmtStateMachine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmtStateMachineTest {

    @Test
    void followsStandardLifecycle() {
        NmtStateMachine nmt = new NmtStateMachine();
        assertEquals(NmtState.INITIALISATION, nmt.getState());

        nmt.enterPreOperational();
        assertEquals(NmtState.PRE_OPERATIONAL, nmt.getState());

        assertTrue(nmt.apply(NmtCommand.START));
        assertEquals(NmtState.OPERATIONAL, nmt.getState());

        assertTrue(nmt.apply(NmtCommand.STOP));
        assertEquals(NmtState.STOPPED, nmt.getState());

        assertTrue(nmt.apply(NmtCommand.ENTER_PRE_OPERATIONAL));
        assertEquals(NmtState.PRE_OPERATIONAL, nmt.getState());

        assertTrue(nmt.apply(NmtCommand.START));
        assertTrue(nmt.apply(NmtCommand.ENTER_PRE_OPERATIONAL));
        assertEquals(NmtState.PRE_OPERATIONAL, nmt.getState());
    }

    @Test
    void rejectsOperationalCommandsDuringInitialisation() {
        NmtStateMachine nmt = new NmtStateMachine();
        assertFalse(nmt.apply(NmtCommand.START));
        assertFalse(nmt.apply(NmtCommand.STOP));
        assertEquals(NmtState.INITIALISATION, nmt.getState());

        assertTrue(nmt.apply(NmtCommand.RESET_COMMUNICATION));
        assertEquals(NmtState.INITIALISATION, nmt.getState());
    }

    @Test
    void resetTransitionsAreReported() {
        NmtStateMachine nmt = new NmtStateMachine();
        List<String> events = new ArrayList<>();
        nmt.addListener(new com.github.guoyyit.canopenstackjava.nmt.NmtListener() {
            @Override
            public void stateChanged(NmtState oldState, NmtState newState) {
                events.add(oldState + "->" + newState);
            }

            @Override
            public void nodeReset() {
                events.add("NODE_RESET");
            }

            @Override
            public void communicationReset() {
                events.add("COMM_RESET");
            }
        });

        nmt.enterPreOperational();
        nmt.apply(NmtCommand.START);
        nmt.apply(NmtCommand.RESET_NODE);

        assertEquals(List.of("INITIALISATION->PRE_OPERATIONAL",
                "PRE_OPERATIONAL->OPERATIONAL",
                "OPERATIONAL->INITIALISATION", "NODE_RESET"), events);
    }

    @Test
    void stateCommunicationGates() {
        assertTrue(NmtState.PRE_OPERATIONAL.allowsSdo());
        assertTrue(NmtState.OPERATIONAL.allowsSdo());
        assertFalse(NmtState.STOPPED.allowsSdo());
        assertTrue(NmtState.OPERATIONAL.allowsPdo());
        assertFalse(NmtState.PRE_OPERATIONAL.allowsPdo());
        assertFalse(NmtState.STOPPED.allowsPdo());
    }
}
