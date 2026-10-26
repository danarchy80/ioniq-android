# IONIQ PID Expansion Plan

## Goal
Incorporate the full ECU/PID map reverse-engineered from the GJSoft IONIQ 5 OBD2 Monitor app into the ioniq-android data layer.

## Scope — Phase 1 (this dispatch)

### Files to create:
1. `app/src/main/kotlin/com/ioniq/obd/EcuConfig.kt` — ECU address definitions
2. `app/src/main/kotlin/com/ioniq/obd/IoniqPids.kt` — All DIDs organized by ECU
3. `app/src/main/kotlin/com/ioniq/obd/BmsParsers.kt` — BMS Main + Status parsers
4. `app/src/main/kotlin/com/ioniq/obd/VehicleParsers.kt` — VCU, Motor, HVAC, Cluster, Body, ESC parsers
5. `app/src/main/kotlin/com/ioniq/util/CapacityCalibrator.kt` — Self-measuring capacity
6. `app/src/main/kotlin/com/ioniq/util/VinDecoder.kt` — VIN decode (WMI, plant, year, model)

### Files to modify:
7. `app/src/main/kotlin/com/ioniq/data/model/VehicleTelemetry.kt` — Add new fields
8. `app/src/main/kotlin/com/ioniq/obd/ObdPids.kt` — Add new ECU header constants

## Exact Specifications

### EcuConfig.kt
```kotlin
package com.ioniq.obd

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
```

### IoniqPids.kt
All DIDs organized by ECU. Key DIDs:
- BMS: 220101 (main), 220105 (status), 220106 (charge stats + thermal), 220111 (lifetime), 220102-22010C (cells)
- VCU: 220101 (drive status)
- MOTOR: 220101 (motor data)
- HVAC: 220100 (climate)
- CLUSTER: 22B002 (main), 22B00C (trips)
- BODY: 22BC03 (doors), 22BC06 (lights/body), 22BC09 (lights)
- ESC: 220101 (dynamics)
- ICCU: 22E002 (OBC), 22E011/22E101 (12V legacy/facelift), 22E021 (OBC AC)
- VCMS: 22E001, 22E002, 22E003 (charging mgmt)

### BMS Main (220101 @ 7E4) — Byte offsets (from GJSoft reverse):
- byte 4: SOC BMS (÷2 = %)
- byte 7: requested charge current (u16 ÷10 = A)
- byte 9: status flags
- byte 10: current (i16 ÷10 = A, negative = charging)
- byte 12: voltage (u16 ÷10 = V)
- byte 14: temp max (signed byte = °C)
- byte 15: temp min (signed byte = °C)
- bytes 16-20: T01-T05 (signed bytes = °C)
- byte 23: cell V max (÷50 = V)
- byte 24: cell max number
- byte 25: cell V min (÷50 = V)
- byte 26: cell min number
- byte 28: cell count (136-215 range)
- byte 29: aux 12V (×0.1 = V)
- bytes 30-33: cumulative charge Ah (u32 ÷10)
- bytes 34-37: cumulative discharge Ah (u32 ÷10)
- bytes 38-41: cumulative charge kWh (u32 ÷10)
- bytes 42-45: cumulative discharge kWh (u32 ÷10)
- bytes 46-49: operating time (u32 ÷3600 = hours)
- bytes 51-52: cap voltage (u16)
- bytes 53-54: RPM rear (i16)
- bytes 55-56: RPM front (i16)
- bytes 57-58: insulation resistance (u16 = kΩ)

### BMS Status (220105 @ 7E4) — Byte offsets:
- byte 9: module temp
- byte 10: available charge power (u16 ÷10 = kW)
- byte 16: max charge power (u16 ÷100 = kW)
- byte 17: high-temp minutes (u16)
- byte 18: max discharge power (u16 ÷100 = kW)
- byte 23: heater temp (signed byte)
- byte 25: SOH (u16 ÷10 = %)
- byte 28: remaining energy (u16 ×2 ÷1000 = kWh)
- byte 31: displayed SOC (÷2 = %)
- byte 36: heater power (u16 ÷10 = kW)
- byte 38: heater status
- byte 39: module temp 2
- byte 43: fan status
- byte 44: fan speed

### VCU (220101 @ 7E2) — Byte offsets:
- byte 0: gear (0=P, 1=R, 2=N, 3=D)
- byte 1: ready state (0=off, 1=ready)
- bytes 2-3: speed (u16 ÷10 = km/h)
- byte 4: accel pedal (÷2 = %)
- byte 5: brake pedal (0/1)
- bytes 6-7: torque request (i16 ÷10 = Nm)
- bytes 8-9: torque output (i16 ÷10 = Nm)
- bytes 10-11: motor RPM (i16)
- bytes 12-13: max drive power (u16 ÷10 = kW)
- bytes 14-15: max regen power (u16 ÷10 = kW)

### Motor (220101 @ 7E3) — Byte offsets:
- bytes 0-1: RPM (i16)
- byte 2: stator temp (signed byte = °C)
- byte 3: inverter temp (signed byte = °C)
- bytes 4-5: torque (i16 ÷10 = Nm)
- bytes 6-7: DC link voltage (u16 ÷10 = V)
- bytes 8-9: DC link current (i16 ÷10 = A)
- bytes 10-11: phase current (u16 ÷10 = A)

### HVAC (220100 @ 7B3) — Byte offsets:
- bytes 3-4: compressor speed (u16 = RPM)
- byte 5: cabin temp (÷2 − 40 = °C)
- byte 6: outside temp (÷2 − 40 = °C)
- byte 7: evaporator temp (÷2 − 40 = °C)
- byte 8: vent passenger temp (÷2 − 40 = °C)
- byte 9: coolant in temp (÷2 − 40 = °C)
- byte 10: coolant out temp (÷2 − 40 = °C)
- byte 11: compressor power (×0.1 = kW)
- byte 12: refrigerant pressure (×0.1 = bar)
- byte 25: humidity (1-99 = %)
- byte 27: vent air temp (÷2 − 40 = °C)
- byte 28: vent legs temp (÷2 − 40 = °C)
- byte 29: compressor inlet temp (signed − 40 = °C)

### Cluster (22B002 @ 7C6) — Byte offsets:
- bytes 1-2: display speed (u16 ÷100 = km/h)
- byte 5: displayed SOC (÷2 = %)
- bytes 6-8: odometer km (u24)
- bytes 9-11: odometer miles (u24) / range (u16)

### Cluster Trips (22B00C @ 7C6):
- bytes 0-3: trip A distance (u32 ÷10 = km)
- bytes 4-7: trip B distance (u32 ÷10 = km)
- bytes 8-9: trip A consumption (u16 ÷10 = kWh/100km)
- bytes 10-11: trip B consumption (u16 ÷10 = kWh/100km)

### Body Doors (22BC03 @ 770):
- byte 4: bit flags — bit5=driver, bit4=passenger, bit0=rearLeft, bit2=rearRight, bit7=tailgate
- byte 5: bit0=hood

### Body Lights (22BC06 @ 770):
- byte 4: brake lights (nonzero = on)
- byte 6: bit5=headlamp, bit5=fog
- byte 7: bits 0-1 = high beam

### ESC (220101 @ 730) — little-endian u16:
- bytes 4-5: wheel FR (÷32 = km/h)
- bytes 6-7: wheel FL (÷32 = km/h)
- bytes 8-9: wheel RR (÷32 = km/h)
- bytes 10-11: wheel RL (÷32 = km/h)
- bytes 12-13: steering angle (i16 LE ÷10 = degrees, negated)
- bytes 14-15: longitudinal accel (u16 LE, (val×8.354/65535)−4.177 = g)
- bytes 16-17: lateral accel (u16 LE, same formula)

### ICCU Facelift Detection:
- Legacy: 22E011, Facelift: 22E101
- Try both, remember which works per VIN in SharedPreferences

### CapacityCalibrator:
- Records (SOC%, remainingEnergy kWh) pairs at min/max SOC
- When span ≥25%: slope = Δenergy / ΔSOC
- When span ≥60%: mark "verified"
- usableCapacity = slope × (100 - currentSOC) + currentRemaining
- SOH correction: slope *= currentSOH / referenceSOH
- Bounds: 25-130 kWh, slope 0.3-1.5
- Fallback: remainingEnergy × 93 / (SOC - 7) when SOC > 9%
- Persist per-VIN in SharedPreferences

### VinDecoder:
- WMI: KMH/KM8/5NM/TMA = Hyundai, KN/5XY/U5Y/U6Y = Kia
- Plant: U=Ulsan, M=Montgomery, A=Anseong
- Year: position 9, codes "ABCDEFGHJKLMNPRSTVWXY123456789" → 2010+
- Country: 5=USA, T=Czech, K=Korea, L=China
- Validate: 17 chars, no I/O/Q, valid year code at pos 9

## VehicleTelemetry new fields:
- bmsSoc, bmsCurrent, bmsVoltage (from 220101)
- soh, maxChargePowerKw, maxDischargePowerKw, remainingEnergyKwh (from 220105)
- thermalMode (from 220106)
- heaterStatus, heaterPowerKw (from 220105)
- vcuSpeed, vcuGear, vcuReady, vcuTorqueOutput, vcuMotorRpm (from VCU)
- motorStatorTemp, motorInverterTemp, motorDcLinkVoltage, motorDcLinkCurrent (from Motor)
- hvacCabinTemp, hvacOutsideTemp, hvacCompressorSpeed, hvacCoolantIn, hvacCoolantOut (from HVAC)
- clusterDisplaySpeed, clusterRange, tripADistance, tripBDistance (from Cluster)
- doorDriverOpen, doorPassengerOpen, doorRearLeftOpen, doorRearRightOpen, hoodOpen, tailgateOpen (from Body)
- usableCapacityKwh (from CapacityCalibrator)

## Output format
Write all files, run `./gradlew assembleDebug` to verify compilation, report:
1. Files created/modified
2. Build result
3. Any concerns or deviations from spec
