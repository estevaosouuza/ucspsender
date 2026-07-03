package com.ucsp.sender.adaptive

import com.ucsp.sender.network.BackchannelReport

/**
 * Phase 2 will consume backchannel reports and thermal status here to adjust the
 * encoder's bitrate/resolution/fps. Phase 1 wires the call sites in but leaves the logic
 * as a no-op, so the rest of the pipeline doesn't need to change shape later.
 */
class AdaptiveController {

    fun onBackchannelReport(report: BackchannelReport) {
        // no-op in Phase 1
    }

    fun onThermalStatusChanged(status: Int) {
        // no-op in Phase 1
    }
}
