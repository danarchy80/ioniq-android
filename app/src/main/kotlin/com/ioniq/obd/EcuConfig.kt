package com.ioniq.obd

/**
 * ECU addressing definitions for the Hyundai IONIQ 5 CAN network.
 *
 * Each ECU is reached by switching the ELM327 send header (ATSH <tx>) and
 * filtering responses on <rx>. Mode 22 (UDS ReadDataByIdentifier) requests
 * are answered with `62 <DID hi> <DID lo> <payload...>`.
 *
 * Byte offsets used by the parsers in this package are payload-relative:
 * they index the message AFTER the 3-byte `62 <DID>` header is stripped
 * (same convention as ObdParser.udsData() and the OBDb signal set).
 */
data class EcuConfig(
    val name: String,
    val txHeader: String,  // e.g. "7E4"
    val rxHeader: String,  // e.g. "7EC"
    val description: String,
    val needsExtendedSession: Boolean = false
) {
    companion object {
        val VCU = EcuConfig("VCU", "7E2", "7EA", "Vehicle Control Unit")
        val BMS = EcuConfig("BMS", "7E4", "7EC", "Battery Management System")
        val ICCU = EcuConfig("ICCU", "7E5", "7ED", "Integrated Charging Control Unit")
        val MOTOR = EcuConfig("Motor", "7E3", "7EB", "Motor / Inverter ECU")
        val CLUSTER = EcuConfig("Cluster", "7C6", "7CE", "Instrument Cluster")
        val HVAC = EcuConfig("HVAC", "7B3", "7BB", "HVAC System")
        val BCM = EcuConfig("BCM", "7A0", "7A8", "Body Control Module")
        val ESC = EcuConfig("ESC", "730", "738", "Stability Control / Dynamics")
        val BODY = EcuConfig("Body", "770", "778", "Body Gateway / Lighting", needsExtendedSession = true)
        val VCMS = EcuConfig("VCMS", "744", "74C", "Vehicle Charging Management", needsExtendedSession = true)

        val ALL = listOf(VCU, BMS, ICCU, MOTOR, CLUSTER, HVAC, BCM, ESC, BODY, VCMS)
    }
}
