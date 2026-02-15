/*******************************************************************************
 * Copyright (C) 2026, Eclipse EGit contributors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.egit.ui.internal.pullrequest;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

/**
 * Paints a "+" icon in the left margin of a StyledText widget when hovering
 * over a line, allowing users to create new inline comments on that line.
 */
class AddCommentMarginPainter
		implements PaintListener, MouseMoveListener, MouseListener {

	private static final int MARGIN_WIDTH = 20;

	private static final int ICON_SIZE = 14;

	private final StyledText styledText;

	private final NewCommentHandler handler;

	private Integer hoveredLine;

	private Cursor handCursor;

	private Cursor defaultCursor;

	/**
	 * Functional interface for handling new comment requests
	 */
	@FunctionalInterface
	public interface NewCommentHandler {
		/**
		 * Called when the user clicks the "+" icon to create a new comment
		 *
		 * @param line
		 *            the 1-based line number
		 */
		void handleNewComment(int line);
	}

	/**
	 * Creates a new AddCommentMarginPainter
	 *
	 * @param styledText
	 *            the StyledText widget to paint on
	 * @param handler
	 *            the handler for new comment requests
	 */
	public AddCommentMarginPainter(StyledText styledText,
			NewCommentHandler handler) {
		this.styledText = styledText;
		this.handler = handler;
	}

	/**
	 * Installs the painter on the StyledText widget
	 */
	public void install() {
		styledText.setLeftMargin(
				styledText.getLeftMargin() + MARGIN_WIDTH);
		styledText.addPaintListener(this);
		styledText.addMouseMoveListener(this);
		styledText.addMouseListener(this);

		handCursor = new Cursor(styledText.getDisplay(), SWT.CURSOR_HAND);
		defaultCursor = styledText.getCursor();
	}

	/**
	 * Uninstalls the painter from the StyledText widget
	 */
	public void uninstall() {
		styledText.setLeftMargin(
				Math.max(0, styledText.getLeftMargin() - MARGIN_WIDTH));
		styledText.removePaintListener(this);
		styledText.removeMouseMoveListener(this);
		styledText.removeMouseListener(this);

		if (handCursor != null && !handCursor.isDisposed()) {
			handCursor.dispose();
			handCursor = null;
		}
	}

	@Override
	public void paintControl(PaintEvent e) {
		if (hoveredLine == null) {
			return;
		}

		try {
			// Get the line start offset
			int lineIndex = hoveredLine.intValue() - 1; // Convert to 0-based
			if (lineIndex < 0 || lineIndex >= styledText.getLineCount()) {
				return;
			}

			int lineOffset = styledText.getOffsetAtLine(lineIndex);
			Point lineLocation = styledText.getLocationAtOffset(lineOffset);
			int lineHeight = styledText.getLineHeight(lineOffset);

			// Calculate icon position in the margin area
			int iconX = 3;
			int iconY = lineLocation.y + (lineHeight - ICON_SIZE) / 2;

			// Draw the "+" icon
			drawPlusIcon(e.gc, iconX, iconY);

		} catch (IllegalArgumentException ex) {
			// Line no longer exists, ignore
		}
	}

	/**
	 * Draws a "+" icon (circle with plus sign)
	 *
	 * @param gc
	 *            the graphics context to draw on
	 * @param x
	 *            the x coordinate for the icon
	 * @param y
	 *            the y coordinate for the icon
	 */
	private void drawPlusIcon(GC gc, int x, int y) {
		Color oldBackground = gc.getBackground();
		Color oldForeground = gc.getForeground();

		// Draw circle background
		gc.setBackground(
				styledText.getDisplay().getSystemColor(SWT.COLOR_WHITE));
		gc.setForeground(
				styledText.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
		gc.setLineWidth(1);
		gc.fillOval(x, y, ICON_SIZE, ICON_SIZE);
		gc.drawOval(x, y, ICON_SIZE, ICON_SIZE);

		// Draw plus sign
		int center = ICON_SIZE / 2;
		int plusSize = ICON_SIZE / 3;
		gc.setLineWidth(2);
		// Horizontal line
		gc.drawLine(x + center - plusSize, y + center, x + center + plusSize,
				y + center);
		// Vertical line
		gc.drawLine(x + center, y + center - plusSize, x + center,
				y + center + plusSize);

		gc.setBackground(oldBackground);
		gc.setForeground(oldForeground);
		gc.setLineWidth(1);
	}

	@Override
	public void mouseMove(MouseEvent e) {
		// Check if mouse is in the margin area
		if (e.x < MARGIN_WIDTH) {
			try {
				// Determine which line is being hovered
				int offset = styledText.getOffsetAtPoint(
						new Point(MARGIN_WIDTH + 1, e.y));
				int lineIndex = styledText.getLineAtOffset(offset);
				int newHoveredLine = lineIndex + 1; // Convert to 1-based

				if (hoveredLine == null
						|| hoveredLine.intValue() != newHoveredLine) {
					hoveredLine = Integer.valueOf(newHoveredLine);
					styledText.redraw();
				}

				// Change cursor to hand
				if (styledText.getCursor() != handCursor) {
					styledText.setCursor(handCursor);
				}
			} catch (IllegalArgumentException ex) {
				// No character at this location
				clearHover();
			}
		} else {
			clearHover();
		}
	}

	private void clearHover() {
		if (hoveredLine != null) {
			hoveredLine = null;
			styledText.redraw();
		}
		if (styledText.getCursor() == handCursor) {
			styledText.setCursor(defaultCursor);
		}
	}

	@Override
	public void mouseDown(MouseEvent e) {
		// Only handle left mouse button
		if (e.button != 1) {
			return;
		}

		// Check if click is in the margin area
		if (e.x < MARGIN_WIDTH && hoveredLine != null) {
			handler.handleNewComment(hoveredLine.intValue());
		}
	}

	@Override
	public void mouseUp(MouseEvent e) {
		// Not needed
	}

	@Override
	public void mouseDoubleClick(MouseEvent e) {
		// Not needed
	}
}
