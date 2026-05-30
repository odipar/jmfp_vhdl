/**
 * Refactored implementation of the MC68901 Multi-Function Peripheral chip.
 *
 * <p>This package contains a structurally refactored version of
 * {@link org.jmfp.vhdl.optimized.Mc68901Optimized} with reduced cyclomatic
 * complexity and fewer lines of code while maintaining bit-identical behaviour.
 *
 * <h2>Refactoring highlights</h2>
 * <ul>
 *   <li>Grouped/merged statement blocks that share the same entry conditional.</li>
 *   <li>Factored out reset, register reads/writes, and timer advancements.</li>
 *   <li>Restructured ordering to prevent unnecessary assignments.</li>
 *   <li>Restructured timers in a uniform fashion.</li>
 *   <li>Reduced cyclomatic complexity and total lines of code.</li>
 * </ul>
 *
 * @see org.jmfp.vhdl.optimized.Mc68901Optimized
 */
package org.jmfp.vhdl.refactored;
