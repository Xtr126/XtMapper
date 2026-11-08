package xtr.keymapper

import org.junit.Assert.*
import org.junit.Test

class InputEventLabelsTest {
    @Test fun generatedConstantsPreserveAliasesAndRanges() {
        assertEquals(272, InputEventCodes.BTN_MOUSE)
        assertEquals(InputEventCodes.BTN_LEFT, InputEventCodes.BTN_MOUSE)
        assertEquals(InputEventCodes.KEY_HANGEUL, InputEventCodes.KEY_HANGUEL)
        assertEquals(InputEventCodes.KEY_MAX + 1, InputEventCodes.KEY_CNT)
    }

    @Test fun labelsUseTheEventNamespaceAndCanonicalNames() {
        assertEquals("KEY_A", InputEventLabels.label(InputEventCodes.EV_KEY, InputEventCodes.KEY_A))
        assertEquals("BTN_MOUSE", InputEventLabels.label(InputEventCodes.EV_KEY, InputEventCodes.BTN_LEFT))
        assertEquals("REL_X", InputEventLabels.label(InputEventCodes.EV_REL, 0))
        assertEquals("SYN_REPORT", InputEventLabels.label(InputEventCodes.EV_SYN, 0))
        assertEquals("ABS_X", InputEventLabels.label(InputEventCodes.EV_ABS, 0))
        assertEquals("KEY_HANGEUL", InputEventLabels.keyLabels[InputEventCodes.KEY_HANGUEL])
        assertNull(InputEventLabels.label(InputEventCodes.EV_KEY, InputEventCodes.KEY_MAX))
        assertNull(InputEventLabels.label(-1, 0))
    }
}
