package cn.cy.timewheel.exception;

public class MinimumIntervalException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public MinimumIntervalException(long should, long real) {
		super("BlockingQueueTimer Minimum Interval should not less than " + should
				+ ", now is " + real);
	}
}
