/**
 * Idiomatic Java refactoring of the MC68901 Multi-Function Peripheral model.
 *
 * <p>This package provides a structured, well-factored alternative to the
 * monolithic {@link org.jmfp.vhdl.Mc68901} class while maintaining
 * bit-identical cycle-accurate behaviour.
 *
 * <ul>
 *   <li>{@link org.jmfp.vhdl.idiomatic.TimerUnit} – shared prescaler /
 *       counter / timeout logic for all four timers.</li>
 *   <li>{@link org.jmfp.vhdl.idiomatic.RegisterFile} – register address
 *       decoding and read/write dispatch.</li>
 *   <li>{@link org.jmfp.vhdl.idiomatic.Mc68901Idiomatic} – top-level chip
 *       model with both low-level cycle-accurate and high-level convenience
 *       APIs.</li>
 * </ul>
 */
package org.jmfp.vhdl.idiomatic;
