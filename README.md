# jmfp_vhdl

Cycle-accurate Java implementation of the **MC68901 Multi-Function Peripheral** (MFP) chip, derived from an open-source VHDL specification.

## Design Methodology

### 1. VHDL Specification

The starting point is [`vhdl/mc68901.vhd`](vhdl/mc68901.vhd), a complete register-transfer-level VHDL description of the MC68901 MFP chip.  
This file defines every port, internal register, timer unit, interrupt controller, and USART behaviour at the individual clock-edge level.

### 2. Testbench and VCD Output

[`vhdl/testbench/tb_feat_mc68901.vhd`](vhdl/testbench/tb_feat_mc68901.vhd) is a self-contained VHDL testbench that exercises the full feature set of the chip:

| Simulation Phase | Feature Exercised |
|---|---|
| Phase 1 (0–80 ms) | Timer A delay mode with mid-phase frequency change |
| Phase 2 (80–165 ms) | Timer B event-count mode with mid-phase count change |
| Phase 3 (165–265 ms) | Timers C and D delay mode with mid-phase frequency change |
| Phase 4 (265–360 ms) | GPIP edge-detect interrupts with mid-phase polarity change |
| Phase 5 (360–500 ms) | All four timers simultaneously with mid-phase frequency change |

Simulating the testbench with any IEEE-compliant VHDL simulator produces a **Value Change Dump** (VCD) file that records the exact signal levels at every time step over a 500 ms simulation window.  
The pre-generated output is stored as [`vhdl/simulations/tb_feat_mc68901.vcd.zip`](vhdl/simulations/tb_feat_mc68901.vcd.zip).

### 3. Cycle-Accurate Java Implementation

The VCD file serves as the *golden reference* for a Java re-implementation.  
[`src/main/java/org/jmfp/vhdl/Mc68901.java`](src/main/java/org/jmfp/vhdl/Mc68901.java) is a 1:1 translation of the VHDL entity into Java: every port maps to a method parameter or getter, every register maps to a private field, and `risingEdge()` replicates the synchronous VHDL `process` block clock-edge by clock-edge.

A conformance test ([`Mc68901ConformanceTest`](src/test/java/org/jmfp/vhdl/Mc68901ConformanceTest.java)) replays the VCD through a `VcdConformanceRunner` that drives the Java model with each recorded input vector and asserts that every output matches the VHDL simulation to the bit.

### 4. Additional Refinements

Once the baseline 1:1 translation passes all conformance tests, the Java model is progressively refined in three further packages while preserving bit-identical behaviour:

| Package | Class | Description |
|---|---|---|
| `org.jmfp.vhdl` | `Mc68901` | 1:1 translation from VHDL; no structural changes |
| `org.jmfp.vhdl.idiomatic` | `Mc68901Idiomatic` | Idiomatic Java refactoring: shared `TimerUnit`, `RegisterFile`, clean separation of concerns |
| `org.jmfp.vhdl.optimized` | `Mc68901Optimized` | Zero per-edge heap allocation, bit-manipulation priority encoder, pre-computed prescale table |
| `org.jmfp.vhdl.refactored` | `Mc68901Refactored` | Reduced cyclomatic complexity, merged conditional blocks, uniform timer structure |

Each variant is validated against the same VCD golden reference.

## Building and Testing

```
mvn test
```

The test suite requires Java 17+ and Maven 3.x.  
The VCD zip file must be present at `vhdl/simulations/tb_feat_mc68901.vcd.zip` relative to the project root (this is the default Maven working directory during tests).

## Attribution

The VHDL source file [`vhdl/mc68901.vhd`](vhdl/mc68901.vhd) was written by **Francois Galea** and is used with permission under the terms of the GNU General Public License.

> Copyright © 2020–2026 Francois Galea \<fgalea at free.fr\>

## License

The VHDL source ([`vhdl/mc68901.vhd`](vhdl/mc68901.vhd)) and the accompanying testbench are licensed under the **GNU General Public License v3.0 (GPL-3.0)**.  
Because the Java implementation is a derivative work of the GPL-3.0 VHDL source, the entire project is distributed under the same GPL-3.0 license.

Key implications:
- You may use, study, modify, and distribute this software.
- Any derivative work or software that statically or dynamically links against this code must also be released under GPL-3.0 (or a compatible license) and must make the source available.
- The full license text is available at <https://www.gnu.org/licenses/gpl-3.0.html>.
