/**
 * CPU-optimized implementation of the MC68901 Multi-Function Peripheral chip.
 *
 * <p>This package provides a performance-optimized variant of the
 * {@link org.jmfp.vhdl.idiomatic} implementation with identical behaviour
 * but reduced CPU time through:
 * <ul>
 *   <li>Zero per-edge heap allocation (no Snapshot/TickResult records)</li>
 *   <li>Bit-manipulation priority encoder</li>
 *   <li>Pre-computed prescale lookup table</li>
 *   <li>Inlined pipeline fields instead of array</li>
 * </ul>
 *
 * @see org.jmfp.vhdl.optimized.Mc68901Optimized
 * @see org.jmfp.vhdl.optimized.OptimizedRegisters
 * @see org.jmfp.vhdl.optimized.OptimizedTimerUnit
 */
package org.jmfp.vhdl.optimized;
