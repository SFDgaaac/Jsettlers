/*******************************************************************************
 * Copyright (c) 2015
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package jsettlers.graphics.action;

import go.graphics.event.GOEvent;
import go.graphics.event.GOEventHandler;
import java.util.Optional;
import jsettlers.common.action.Action;

/**
 * This is a go event handler that preforms an action when the event is fired.
 * 
 * @author michael
 */
public class ActionHandler implements GOEventHandler {

	private final Optional<Action> action;
	private final ActionFireable connector;

	/**
	 * This is an action handler converts go events to actions.
	 * 
	 * @param action
	 *            The action
	 * @param connector
	 *            The event to fire the action for.
	 */
	public ActionHandler(Optional<Action> action, ActionFireable connector) {
		this.action = action;
		this.connector = connector;
	}

	@Override
	public void aborted(GOEvent event) {
	}

	@Override
	public void finished(GOEvent event) {
		action.ifPresent(this.connector::fireAction);
	}

	@Override
	public void phaseChanged(GOEvent event) {
	}

}
