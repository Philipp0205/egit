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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.egit.core.internal.bitbucket.PullRequestComment;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Paints inline pull request comment bubbles directly on a StyledText widget
 * using {@link StyledText#setLineVerticalIndent(int, int)} to reserve space
 * and a {@link PaintListener} to draw the comment bubbles.
 *
 * <p>
 * This approach bypasses Eclipse's annotation framework and directly
 * manipulates the StyledText widget, making it compatible with TextMergeViewer
 * which manages its own document lifecycle.
 * </p>
 *
 * <p>
 * Each comment bubble shows only the first comment (author, timestamp, and
 * body text capped at 3 lines) with a summary line showing the number of
 * replies if any exist. Clicking anywhere on the bubble invokes the
 * configured {@link CommentSelectHandler} to select the comment in the
 * comments view, where the user can see the full thread and reply.
 * </p>
 */
public class InlineCommentPainter implements PaintListener {

	/**
	 * Callback interface for handling comment selection actions on inline
	 * comments.
	 */
	@FunctionalInterface
	public interface CommentSelectHandler {

		/**
		 * Called when the user clicks on a comment bubble to select it in
		 * the comments view.
		 *
		 * @param comment
		 *            the comment to select
		 */
		void selectComment(PullRequestComment comment);
	}

	private static final int PADDING_X = 8;

	private static final int PADDING_Y = 4;

	private static final int ARC = 8;

	/**
	 * Maximum number of wrapped lines to display for the comment body.
	 * If the body exceeds this, the last line is truncated with "...".
	 */
	private static final int MAX_BODY_LINES = 3;

	/**
	 * Extra padding below the comment bubble before the code line starts.
	 * This creates visual separation between the comment and the code.
	 */
	private static final int PADDING_BELOW_BUBBLE = 8;

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm"); //$NON-NLS-1$

	private static final RGB COMMENT_BG_RGB = new RGB(219, 234, 254);

	private static final RGB RESOLVED_BG_RGB = new RGB(220, 237, 222);

	private static final RGB AUTHOR_RGB = new RGB(30, 64, 120);

	private static final RGB TEXT_RGB = new RGB(50, 50, 50);

	private static final RGB TIMESTAMP_RGB = new RGB(120, 120, 120);

	private static final RGB BORDER_RGB = new RGB(180, 200, 230);

	private final StyledText styledText;

	private final CommentSelectHandler commentSelectHandler;

	/**
	 * Map from 1-based line numbers to comments. Multiple comments per line are
	 * not yet supported - only the first comment per line is displayed.
	 */
	private final Map<Integer, PullRequestComment> commentsMap = new HashMap<>();

	/**
	 * Map from 1-based line numbers to the pixel bounds of the comment
	 * bubble in the most recent paint cycle. Rebuilt on every
	 * {@link #paintControl(PaintEvent)} call because scroll position changes
	 * the coordinates.
	 */
	private final Map<Integer, Rectangle> bubbleBounds = new HashMap<>();

	private Color bgColor;

	private Color resolvedBgColor;

	private Color authorColor;

	private Color textColor;

	private Color timestampColor;

	private Color borderColor;

	private Cursor handCursor;

	private Cursor defaultCursor;

	private MouseListener mouseListener;

	private MouseMoveListener mouseMoveListener;

	private ControlListener resizeListener;

	/**
	 * Creates a new inline comment painter.
	 *
	 * @param styledText
	 *            the StyledText widget to paint on
	 * @param document
	 *            the document (for line offset calculations)
	 * @param comments
	 *            the list of comments to display
	 * @param commentSelectHandler
	 *            callback invoked when the user clicks on a comment bubble,
	 *            or {@code null} if selection is not supported
	 */
	public InlineCommentPainter(StyledText styledText, IDocument document,
			List<PullRequestComment> comments,
			CommentSelectHandler commentSelectHandler) {
		this.styledText = styledText;
		this.commentSelectHandler = commentSelectHandler;

		// Build comments map
		for (PullRequestComment comment : comments) {
			if (comment.isInlineComment() && comment.getLine() != null) {
				Integer lineNumber = comment.getLine();
				// For now, only keep the first comment per line
				// TODO: support multiple comments per line
				if (!commentsMap.containsKey(lineNumber)) {
					commentsMap.put(lineNumber, comment);
				}
			}
		}
	}

	/**
	 * Installs this painter on the StyledText widget. This sets vertical
	 * indents for commented lines and adds the paint listener and mouse
	 * listeners for the Reply button.
	 */
	public void install() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}

		ensureColors();

		// Set vertical indent for each commented line
		for (Map.Entry<Integer, PullRequestComment> entry : commentsMap
				.entrySet()) {
			int oneBasedLine = entry.getKey().intValue();
			PullRequestComment comment = entry.getValue();

			try {
				// The comment line number is 1-based and refers to the line
				// the comment is about. The bubble should appear ABOVE that
				// line. In StyledText we use 0-based line indices, so we use
				// oneBasedLine directly as the 0-based index (one line down).
				int lineIndex = oneBasedLine;

				int styledTextLineCount = styledText.getLineCount();

				if (lineIndex >= 0 && lineIndex < styledTextLineCount) {
					int height = calculateCommentHeight(comment);
					styledText.setLineVerticalIndent(lineIndex, height);
				}
			} catch (Exception e) {
				org.eclipse.egit.ui.Activator.logError(
						"Failed to set vertical indent for comment at line " //$NON-NLS-1$
								+ oneBasedLine,
						e);
			}
		}

		// Add paint listener to draw comment bubbles
		styledText.addPaintListener(this);

		// Add mouse listeners for Reply button and toggle button interaction
		installMouseListeners();

		// Add control listener for window resize
		resizeListener = ControlListener.controlResizedAdapter(e -> {
			// Recalculate all vertical indents when window is resized
			for (Map.Entry<Integer, PullRequestComment> entry : commentsMap.entrySet()) {
				PullRequestComment comment = entry.getValue();
				int oneBasedLine = entry.getKey().intValue();
				int lineIndex = oneBasedLine;

				if (lineIndex >= 0 && lineIndex < styledText.getLineCount()) {
					int newHeight = calculateCommentHeight(comment);
					styledText.setLineVerticalIndent(lineIndex, newHeight);
				}
			}
			styledText.redraw();
		});
		styledText.addControlListener(resizeListener);

		// Force layout recalculation and redraw
		styledText.setRedraw(false);
		try {
			int topIndex = styledText.getTopIndex();
			styledText.setTopIndex(0);
			styledText.setTopIndex(topIndex);
		} finally {
			styledText.setRedraw(true);
		}

		styledText.redraw();
		styledText.update();

		styledText.getDisplay().asyncExec(() -> {
			if (!styledText.isDisposed()) {
				styledText.redraw();
				styledText.update();
			}
		});

		styledText.getDisplay().timerExec(100, () -> {
			if (!styledText.isDisposed()) {
				styledText.redraw();
				styledText.update();
			}
		});
	}

	/**
	 * Uninstalls this painter from the StyledText widget. This removes
	 * vertical indents, the paint listener, and mouse listeners.
	 */
	public void uninstall() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}

		// Remove paint listener
		styledText.removePaintListener(this);

		// Remove mouse listeners
		uninstallMouseListeners();

		// Remove resize listener
		if (resizeListener != null) {
			styledText.removeControlListener(resizeListener);
			resizeListener = null;
		}

		// Reset vertical indents
		for (Map.Entry<Integer, PullRequestComment> entry : commentsMap
				.entrySet()) {
			int oneBasedLine = entry.getKey().intValue();
			int lineIndex = oneBasedLine;

			if (lineIndex >= 0
					&& lineIndex < styledText.getLineCount()) {
				styledText.setLineVerticalIndent(lineIndex, 0);
			}
		}

		// Dispose colors and cursors
		disposeColors();
		disposeCursors();
		bubbleBounds.clear();

		// Force redraw
		styledText.redraw();
	}

	@Override
	public void paintControl(PaintEvent e) {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}

		GC gc = e.gc;
		int clientHeight = styledText.getClientArea().height;

		// Clear previous bubble bounds — they are rebuilt each paint
		bubbleBounds.clear();

		for (Map.Entry<Integer, PullRequestComment> entry : commentsMap
				.entrySet()) {
			int oneBasedLine = entry.getKey().intValue();
			PullRequestComment comment = entry.getValue();
			int lineIndex = oneBasedLine;

			if (lineIndex < 0
					|| lineIndex >= styledText.getLineCount()) {
				continue;
			}

			int verticalIndent = styledText
					.getLineVerticalIndent(lineIndex);

			if (verticalIndent <= 0) {
				continue;
			}

			int lineOffset = styledText.getOffsetAtLine(lineIndex);
			Point location = styledText.getLocationAtOffset(lineOffset);
			int lineTextY = location.y;
			int bubbleTop = lineTextY - verticalIndent;

			if (lineTextY < 0 || bubbleTop > clientHeight) {
				continue;
			}

			int clientWidth = styledText.getClientArea().width;
			int bubbleHeight = verticalIndent - PADDING_BELOW_BUBBLE;

			// Fill the entire vertical indent area with the editor background
			gc.setBackground(styledText.getBackground());
			gc.fillRectangle(0, bubbleTop, clientWidth, verticalIndent);

			drawCommentBubble(gc, comment, oneBasedLine, PADDING_X,
					bubbleTop, clientWidth, bubbleHeight);
		}
	}

	/**
	 * Draws a comment bubble at the specified position, showing only the
	 * first comment (author, timestamp, body capped at 3 lines) and a
	 * summary of the number of replies if any exist.
	 *
	 * @param gc
	 *            the graphics context
	 * @param comment
	 *            the comment to draw
	 * @param oneBasedLine
	 *            the 1-based line number for this comment (key into
	 *            {@link #bubbleBounds})
	 * @param x
	 *            the left x coordinate
	 * @param y
	 *            the top y coordinate
	 * @param width
	 *            the available width
	 * @param height
	 *            the height of the bubble
	 */
	private void drawCommentBubble(GC gc, PullRequestComment comment,
			int oneBasedLine, int x, int y, int width, int height) {
		boolean isResolved = "RESOLVED".equals(comment.getState()); //$NON-NLS-1$

		// Draw background
		gc.setBackground(isResolved ? resolvedBgColor : bgColor);
		gc.fillRoundRectangle(x, y + 2, width - 2 * PADDING_X, height - 4,
				ARC, ARC);

		// Draw border
		gc.setForeground(borderColor);
		gc.drawRoundRectangle(x, y + 2, width - 2 * PADDING_X, height - 4,
				ARC, ARC);

		// Store bubble bounds for click detection
		bubbleBounds.put(Integer.valueOf(oneBasedLine),
				new Rectangle(x, y + 2, width - 2 * PADDING_X, height - 4));

		FontMetrics fm = gc.getFontMetrics();
		int lineHeight = fm.getHeight() + 2;
		int textX = x + PADDING_X;
		int currentY = y + PADDING_Y + 2;

		// Calculate available text width
		int availableWidth = width - 2 * PADDING_X - 16;
		if (availableWidth <= 0) {
			availableWidth = 100; // Fallback minimum
		}

		// Draw author + timestamp line
		String author = comment.getAuthorDisplayName();
		if (author == null || author.isEmpty()) {
			author = comment.getAuthorName();
		}
		if (author == null) {
			author = "Unknown"; //$NON-NLS-1$
		}
		String timestamp = ""; //$NON-NLS-1$
		if (comment.getCreatedDate() != null) {
			synchronized (DATE_FORMAT) {
				timestamp = DATE_FORMAT.format(comment.getCreatedDate());
			}
		}

		gc.setForeground(authorColor);
		gc.drawString(author, textX, currentY, true);
		int authorWidth = gc.textExtent(author).x;

		gc.setForeground(timestampColor);
		gc.drawString("  " + timestamp, textX + authorWidth, currentY, //$NON-NLS-1$
				true);

		if (isResolved) {
			String resolvedTag = " [RESOLVED]"; //$NON-NLS-1$
			int tsWidth = gc.textExtent("  " + timestamp).x; //$NON-NLS-1$
			gc.drawString(resolvedTag, textX + authorWidth + tsWidth,
					currentY, true);
		}

		currentY += lineHeight;

		// Draw comment body with word wrapping, capped at MAX_BODY_LINES
		gc.setForeground(textColor);
		String bodyText = comment.getText();
		if (bodyText != null && !bodyText.isEmpty()) {
			List<String> bodyLines = wrapText(gc, bodyText, availableWidth);
			int linesToShow = Math.min(bodyLines.size(), MAX_BODY_LINES);

			for (int i = 0; i < linesToShow; i++) {
				String line = bodyLines.get(i);
				// If this is the last line and there are more lines, truncate with "..."
				if (i == MAX_BODY_LINES - 1 && bodyLines.size() > MAX_BODY_LINES) {
					// Measure and truncate to fit "..."
					String ellipsis = "..."; //$NON-NLS-1$
					int ellipsisWidth = gc.textExtent(ellipsis).x;
					int availableForText = availableWidth - ellipsisWidth;
					
					// Truncate line to fit
					while (!line.isEmpty() && gc.textExtent(line).x > availableForText) {
						line = line.substring(0, line.length() - 1);
					}
					line = line + ellipsis;
				}
				gc.drawString(line, textX, currentY, true);
				currentY += lineHeight;
			}
		}

		// Draw reply summary if replies exist
		List<PullRequestComment> replies = comment.getReplies();
		if (replies != null && !replies.isEmpty()) {
			gc.setForeground(authorColor);
			int replyCount = replies.size();
			String replyText = "\u21B3 " + replyCount //$NON-NLS-1$
					+ (replyCount == 1 ? " reply" : " replies"); //$NON-NLS-1$ //$NON-NLS-2$
			gc.drawString(replyText, textX, currentY, true);
		}
	}

	// ---- Text wrapping and height calculation ------------------------------

	/**
	 * Wraps a text string to fit within the given pixel width, breaking at
	 * word boundaries. Existing newlines in the input text are preserved.
	 *
	 * @param gc
	 *            the graphics context for measuring text extents
	 * @param text
	 *            the text to wrap (may be {@code null})
	 * @param maxWidth
	 *            the maximum width in pixels
	 * @return a list of wrapped lines; empty if text is {@code null} or empty
	 */
	private List<String> wrapText(GC gc, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return lines;
		}

		// Split by existing newlines first
		String[] paragraphs = text.split("\\r?\\n"); //$NON-NLS-1$
		for (String paragraph : paragraphs) {
			if (paragraph.isEmpty()) {
				lines.add(""); //$NON-NLS-1$
				continue;
			}

			// Wrap each paragraph to fit the width
			String remaining = paragraph;
			while (!remaining.isEmpty()) {
				// Measure the full remaining text
				int fullWidth = gc.textExtent(remaining).x;
				if (fullWidth <= maxWidth) {
					// Fits entirely
					lines.add(remaining);
					break;
				}

				// Find the longest substring that fits
				int end = remaining.length();
				while (end > 0) {
					String candidate = remaining.substring(0, end);
					if (gc.textExtent(candidate).x <= maxWidth) {
						// Found a fit — now backtrack to last word boundary
						int lastSpace = candidate.lastIndexOf(' ');
						if (lastSpace > 0 && lastSpace < end - 1) {
							// Break at word boundary
							lines.add(candidate.substring(0, lastSpace));
							remaining = remaining.substring(lastSpace + 1);
						} else {
							// No space found or space at end — break at char
							lines.add(candidate);
							remaining = remaining.substring(end);
						}
						break;
					}
					end--;
				}

				if (end == 0) {
					// Pathological case: even single char doesn't fit
					// Just add the first char and continue
					lines.add(remaining.substring(0, 1));
					remaining = remaining.substring(1);
				}
			}
		}

		return lines;
	}

	/**
	 * Calculates the height needed for a comment bubble.
	 *
	 * @param comment
	 *            the comment
	 * @return the height in pixels (includes bubble + padding below)
	 */
	private int calculateCommentHeight(PullRequestComment comment) {
		if (styledText == null || styledText.isDisposed()) {
			return 0;
		}

		GC gc = new GC(styledText);
		try {
			FontMetrics fm = gc.getFontMetrics();
			int lineHeight = fm.getHeight() + 2;

			// Calculate available text width
			int availableWidth = styledText.getClientArea().width - 2 * PADDING_X - 16;
			if (availableWidth <= 0) {
				availableWidth = 100; // Fallback minimum
			}

			// Calculate lines needed:
			// 1 line for author + timestamp
			int totalLines = 1;

			// Lines for main comment body (with word wrapping), capped at MAX_BODY_LINES
			String bodyText = comment.getText();
			if (bodyText != null && !bodyText.isEmpty()) {
				List<String> bodyLines = wrapText(gc, bodyText, availableWidth);
				int linesToShow = Math.min(bodyLines.size(), MAX_BODY_LINES);
				totalLines += linesToShow;
			}

			// 1 line for reply summary if replies exist
			List<PullRequestComment> replies = comment.getReplies();
			if (replies != null && !replies.isEmpty()) {
				totalLines += 1;
			}

			return lineHeight * totalLines + 2 * PADDING_Y + 4
					+ PADDING_BELOW_BUBBLE;
		} finally {
			gc.dispose();
		}
	}

	// ---- Mouse interaction for comment selection --------------------------

	/**
	 * Installs mouse listeners on the StyledText widget for comment bubble
	 * click detection and cursor changes.
	 */
	private void installMouseListeners() {
		handCursor = new Cursor(styledText.getDisplay(), SWT.CURSOR_HAND);
		defaultCursor = styledText.getCursor();

		mouseListener = new MouseListener() {
			@Override
			public void mouseUp(MouseEvent e) {
				// not used
			}

			@Override
			public void mouseDown(MouseEvent e) {
				if (e.button != 1) {
					return;
				}

				// Check if click is on any comment bubble
				if (commentSelectHandler != null) {
					PullRequestComment clickedComment = hitTestBubble(e.x, e.y);
					if (clickedComment != null) {
						commentSelectHandler.selectComment(clickedComment);
					}
				}
			}

			@Override
			public void mouseDoubleClick(MouseEvent e) {
				// not used
			}
		};

		mouseMoveListener = e -> {
			boolean overBubble = false;

			// Check if hovering over any bubble
			if (commentSelectHandler != null) {
				PullRequestComment hoverComment = hitTestBubble(e.x, e.y);
				if (hoverComment != null) {
					overBubble = true;
				}
			}

			// Update cursor
			if (overBubble) {
				if (styledText.getCursor() != handCursor) {
					styledText.setCursor(handCursor);
				}
			} else {
				if (styledText.getCursor() == handCursor) {
					styledText.setCursor(defaultCursor);
				}
			}
		};

		styledText.addMouseListener(mouseListener);
		styledText.addMouseMoveListener(mouseMoveListener);
	}

	/**
	 * Removes mouse listeners from the StyledText widget.
	 */
	private void uninstallMouseListeners() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}
		if (mouseListener != null) {
			styledText.removeMouseListener(mouseListener);
			mouseListener = null;
		}
		if (mouseMoveListener != null) {
			styledText.removeMouseMoveListener(mouseMoveListener);
			mouseMoveListener = null;
		}
		// Restore default cursor
		if (defaultCursor != null) {
			styledText.setCursor(defaultCursor);
		}
	}

	/**
	 * Hit-tests the comment bubble areas against the given coordinates.
	 *
	 * @param x
	 *            the x coordinate (widget-relative)
	 * @param y
	 *            the y coordinate (widget-relative)
	 * @return the comment whose bubble contains the point, or {@code null}
	 *         if no bubble was hit
	 */
	private PullRequestComment hitTestBubble(int x, int y) {
		for (Map.Entry<Integer, Rectangle> entry : bubbleBounds
				.entrySet()) {
			if (entry.getValue().contains(x, y)) {
				return commentsMap.get(entry.getKey());
			}
		}
		return null;
	}

	// ---- Color / cursor management -----------------------------------------

	private void ensureColors() {
		if (styledText == null || styledText.isDisposed()) {
			return;
		}

		if (bgColor == null || bgColor.isDisposed()) {
			bgColor = new Color(styledText.getDisplay(), COMMENT_BG_RGB);
			resolvedBgColor = new Color(styledText.getDisplay(),
					RESOLVED_BG_RGB);
			authorColor = new Color(styledText.getDisplay(), AUTHOR_RGB);
			textColor = new Color(styledText.getDisplay(), TEXT_RGB);
			timestampColor = new Color(styledText.getDisplay(),
					TIMESTAMP_RGB);
			borderColor = new Color(styledText.getDisplay(), BORDER_RGB);
		}
	}

	private void disposeColors() {
		bgColor = null;
		resolvedBgColor = null;
		authorColor = null;
		textColor = null;
		timestampColor = null;
		borderColor = null;
	}

	private void disposeCursors() {
		if (handCursor != null && !handCursor.isDisposed()) {
			handCursor.dispose();
			handCursor = null;
		}
		defaultCursor = null;
	}
}
