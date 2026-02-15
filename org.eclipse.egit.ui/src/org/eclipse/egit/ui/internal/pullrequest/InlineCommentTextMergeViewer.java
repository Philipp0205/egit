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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.bitbucket.PullRequest;
import org.eclipse.egit.core.internal.bitbucket.PullRequestComment;
import org.eclipse.egit.core.internal.pullrequest.IPullRequestClient;
import org.eclipse.egit.core.internal.pullrequest.PullRequestClientFactory;
import org.eclipse.egit.ui.Activator;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

/**
 * Custom {@link TextMergeViewer} subclass that supports displaying inline pull
 * request comments as visual bubbles above commented lines.
 *
 * <p>
 * This viewer uses {@link InlineCommentPainter} to draw comment bubbles
 * directly on the {@link StyledText} widget via
 * {@link StyledText#setLineVerticalIndent(int, int)} and a paint listener. This
 * approach bypasses Eclipse's annotation framework, making it compatible with
 * {@link TextMergeViewer}'s document lifecycle management.
 * </p>
 *
 * <p>
 * Each comment bubble is clickable. Clicking anywhere on a bubble opens the
 * {@link PullRequestCommentsView} and selects that comment, allowing users to
 * view the full thread and reply from there.
 * </p>
 */
public class InlineCommentTextMergeViewer extends TextMergeViewer {

	private SourceViewer leftSourceViewer;

	private SourceViewer rightSourceViewer;

	private InlineCommentPainter leftPainter;

	private InlineCommentPainter rightPainter;

	private AddCommentMarginPainter leftMarginPainter;

	private AddCommentMarginPainter rightMarginPainter;

	/**
	 * Counter for configureTextViewer calls. The call order is always ancestor
	 * (0), left (1), right (2) -- even in 2-way mode where the ancestor pane
	 * is created but hidden.
	 */
	private int configureCount;

	/**
	 * Pending comments to be applied once documents are available.
	 */
	private List<PullRequestComment> pendingComments;

	/**
	 * The most recently applied comments list, kept for refreshing after a
	 * reply is posted.
	 */
	private List<PullRequestComment> currentComments;

	/**
	 * The file path of the currently displayed file. This is needed for
	 * creating new comments when no existing comments are present.
	 */
	private String currentFilePath;

	/**
	 * Constructor.
	 *
	 * @param parent
	 *            the parent composite
	 * @param configuration
	 *            the compare configuration
	 */
	public InlineCommentTextMergeViewer(Composite parent,
			CompareConfiguration configuration) {
		super(parent, configuration);
	}

	@Override
	protected void configureTextViewer(
			org.eclipse.jface.text.TextViewer textViewer) {
		super.configureTextViewer(textViewer);

		// Track left/right SourceViewer references.
		// configureTextViewer is called in order: ancestor (0), left (1),
		// right (2) -- even in 2-way mode (ancestor is created but hidden).
		if (textViewer instanceof SourceViewer) {
			SourceViewer sv = (SourceViewer) textViewer;
			int index = configureCount++;
			if (index == 1) {
				leftSourceViewer = sv;
			} else if (index == 2) {
				rightSourceViewer = sv;
			}
		}
	}

	@Override
	protected void updateContent(Object ancestor, Object left, Object right) {
		super.updateContent(ancestor, left, right);

		if (pendingComments != null) {
			final List<PullRequestComment> commentsToApply = pendingComments;
			pendingComments = null;

			getControl().getDisplay().asyncExec(() -> {
				if (!getControl().isDisposed()) {
					applyCommentsDeferred(commentsToApply);
				}
			});
		}
	}

	/**
	 * Sets the pull request comments to display as inline annotations.
	 *
	 * <p>
	 * Only inline comments (those with a non-null {@code line} and
	 * {@code path}) are rendered. Comments are placed on the left side
	 * ({@code fileType == "FROM"}) or right side ({@code fileType == "TO"}).
	 * </p>
	 *
	 * @param comments
	 *            the list of comments for the current file
	 */
	public void setComments(List<PullRequestComment> comments) {
		// Extract file path from comments for use in creating new comments
		if (comments != null && !comments.isEmpty()) {
			for (PullRequestComment c : comments) {
				if (c.getPath() != null) {
					currentFilePath = c.getPath();
					break;
				}
			}
		}

		// If documents are not yet available, store comments as pending
		boolean documentsReady = (leftSourceViewer != null
				&& leftSourceViewer.getDocument() != null)
				|| (rightSourceViewer != null
						&& rightSourceViewer.getDocument() != null);

		if (!documentsReady) {
			pendingComments = comments;
			return;
		}

		applyComments(comments);
	}

	/**
	 * Sets the file path for the currently displayed file. This is used when
	 * creating new comments, especially when there are no existing comments to
	 * extract the path from.
	 *
	 * @param filePath
	 *            the file path
	 */
	public void setFilePath(String filePath) {
		this.currentFilePath = filePath;
	}

	/**
	 * Applies comments with a deferred retry mechanism. If documents are not
	 * ready yet, this method will retry after a short delay.
	 *
	 * @param comments
	 *            the list of comments to apply
	 */
	private void applyCommentsDeferred(List<PullRequestComment> comments) {
		applyCommentsDeferred(comments, 0);
	}

	/**
	 * Applies comments with a deferred retry mechanism.
	 *
	 * @param comments
	 *            the list of comments to apply
	 * @param retryCount
	 *            the number of retries attempted so far
	 */
	private void applyCommentsDeferred(List<PullRequestComment> comments,
			int retryCount) {
		boolean documentsReady = (leftSourceViewer != null
				&& leftSourceViewer.getDocument() != null
				&& leftSourceViewer.getDocument().getNumberOfLines() > 1)
				|| (rightSourceViewer != null
						&& rightSourceViewer.getDocument() != null
						&& rightSourceViewer.getDocument()
								.getNumberOfLines() > 1);

		if (!documentsReady && retryCount < 5) {
			final int nextRetry = retryCount + 1;
			getControl().getDisplay().timerExec(50, () -> {
				if (!getControl().isDisposed()) {
					applyCommentsDeferred(comments, nextRetry);
				}
			});
			return;
		}

		applyComments(comments);
	}

	/**
	 * Actually applies comments to the viewers. This is called either
	 * immediately from setComments() if documents are ready, or deferred
	 * until updateContent() is called.
	 *
	 * @param comments
	 *            the list of comments to apply
	 */
	private void applyComments(List<PullRequestComment> comments) {
		clearPainters();
		clearMarginPainters();

		// Always install margin painters so users can add the first comment
		installMarginPainters();

		if (comments == null || comments.isEmpty()) {
			return;
		}

		// Keep a reference for refresh after reply
		currentComments = new ArrayList<>(comments);

		// Separate comments by side (LEFT = "FROM", RIGHT = "TO")
		List<PullRequestComment> leftComments = new ArrayList<>();
		List<PullRequestComment> rightComments = new ArrayList<>();

		for (PullRequestComment comment : comments) {
			if (!comment.isInlineComment()) {
				continue;
			}
			if (comment.getLine() == null
					|| comment.getLine().intValue() < 1) {
				continue;
			}

			String fileType = comment.getFileType();
			if ("FROM".equals(fileType)) { //$NON-NLS-1$
				leftComments.add(comment);
			} else {
				rightComments.add(comment);
			}
		}

		// Handler to select comment in PullRequestCommentsView when clicked
		InlineCommentPainter.CommentSelectHandler handler = comment -> {
			try {
				IWorkbenchPage page = PlatformUI.getWorkbench()
						.getActiveWorkbenchWindow().getActivePage();
				if (page == null) {
					return;
				}

				// Open/activate the PullRequestCommentsView
				IViewPart part = page.showView(PullRequestCommentsView.VIEW_ID);
				if (part instanceof PullRequestCommentsView) {
					// Select the clicked comment in the view
					((PullRequestCommentsView) part)
							.selectAndRevealComment(comment);
				}
			} catch (Exception e) {
				Activator.logError(
						"Failed to open comments view", e); //$NON-NLS-1$
			}
		};

		// Install painters for left and right sides
		if (leftSourceViewer != null && !leftComments.isEmpty()) {
			IDocument doc = leftSourceViewer.getDocument();
			StyledText styledText = leftSourceViewer.getTextWidget();
			if (doc != null && styledText != null
					&& !styledText.isDisposed()) {
				leftPainter = new InlineCommentPainter(styledText, doc,
						leftComments, handler);
				leftPainter.install();
			}
		}

		if (rightSourceViewer != null && !rightComments.isEmpty()) {
			IDocument doc = rightSourceViewer.getDocument();
			StyledText styledText = rightSourceViewer.getTextWidget();
			if (doc != null && styledText != null
					&& !styledText.isDisposed()) {
				rightPainter = new InlineCommentPainter(styledText, doc,
						rightComments, handler);
				rightPainter.install();
			}
		}
	}

	// ---- Reply handling ----------------------------------------------------

	/**
	 * Handles a reply action triggered from a comment bubble's Reply link.
	 * Opens a {@link MultiLineInputDialog}, posts the reply via the
	 * configured pull request client, then refreshes both the inline
	 * painters and the {@link PullRequestCommentsView}.
	 *
	 * @param comment
	 *            the comment being replied to
	 */
	private void handleReply(PullRequestComment comment) {
		MultiLineInputDialog dialog = new MultiLineInputDialog(
				getControl().getShell(),
				"Reply to Comment", //$NON-NLS-1$
				"Enter your reply:", //$NON-NLS-1$
				""); //$NON-NLS-1$
		if (dialog.open() != Window.OK) {
			return;
		}

		String replyText = dialog.getValue();
		if (replyText == null || replyText.trim().isEmpty()) {
			return;
		}

		PullRequest pr = getSelectedPullRequest();
		if (pr == null) {
			return;
		}

		Job job = new Job("Posting reply") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					IPullRequestClient client =
							PullRequestClientFactory.createClient();
					if (client == null) {
						return new Status(IStatus.ERROR,
								Activator.PLUGIN_ID,
								"Pull request provider not configured"); //$NON-NLS-1$
					}

					client.addComment(pr.getId(), replyText,
							comment.getId());

					// Refresh both the inline comments and the
					// comments view tree
					refreshAfterReply(pr, client);
					return Status.OK_STATUS;
				} catch (IOException e) {
					Activator.logError(
							"Failed to post reply", e); //$NON-NLS-1$
					return new Status(IStatus.ERROR,
							Activator.PLUGIN_ID,
							"Failed to post reply: " //$NON-NLS-1$
									+ e.getMessage(),
							e);
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	/**
	 * Refreshes both the inline comment painters and the
	 * {@link PullRequestCommentsView} after a reply has been posted.
	 *
	 * @param pr
	 *            the current pull request
	 * @param client
	 *            the pull request client
	 */
	private void refreshAfterReply(PullRequest pr,
			IPullRequestClient client) {
		try {
			List<PullRequestComment> freshComments =
					client.getPullRequestComments(pr.getId());

			Display.getDefault().asyncExec(() -> {
				if (getControl() != null && !getControl().isDisposed()) {
					// Refresh inline painters with fresh comments
					// filtered to the same file
					List<PullRequestComment> fileComments =
							filterCommentsForCurrentFile(freshComments);
					applyComments(fileComments);
				}

				// Refresh the PullRequestCommentsView tree
				refreshCommentsView(freshComments);
			});
		} catch (IOException e) {
			Activator.logError("Failed to refresh comments", e); //$NON-NLS-1$
		}
	}

	/**
	 * Handles creating a new inline comment on a specific line.
	 * Opens a {@link MultiLineInputDialog}, posts the comment via the
	 * configured pull request client, then refreshes both the inline
	 * painters and the {@link PullRequestCommentsView}.
	 *
	 * @param line
	 *            the 0-based line number in the StyledText widget
	 * @param fileType
	 *            "FROM" for left side, "TO" for right side
	 */
	private void handleNewComment(int line, String fileType) {
		MultiLineInputDialog dialog = new MultiLineInputDialog(
				getControl().getShell(),
				"Add Comment", //$NON-NLS-1$
				"Enter your comment:", //$NON-NLS-1$
				""); //$NON-NLS-1$
		if (dialog.open() != Window.OK) {
			return;
		}

		String commentText = dialog.getValue();
		if (commentText == null || commentText.trim().isEmpty()) {
			return;
		}

		PullRequest pr = getSelectedPullRequest();
		if (pr == null) {
			return;
		}

		// Use the stored file path
		if (currentFilePath == null) {
			Activator.logError("Cannot create comment: file path unknown", //$NON-NLS-1$
					null);
			return;
		}

		// Convert 0-based StyledText line to 1-based file line
		final int fileLine = line + 1;
		final String finalFilePath = currentFilePath;

		Job job = new Job("Posting comment") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					IPullRequestClient client =
							PullRequestClientFactory.createClient();
					if (client == null) {
						return new Status(IStatus.ERROR,
								Activator.PLUGIN_ID,
								"Pull request provider not configured"); //$NON-NLS-1$
					}

					// Get commit SHA from pull request
					String commitId = pr.getFromRef().getId();

					// Determine line type (simplified - always use ADDED for
					// now)
					// TODO: Use RangeDifferencer to determine actual line
					// type
					String lineType = "ADDED"; //$NON-NLS-1$

					client.addInlineComment(pr.getId(), commentText,
							finalFilePath, fileLine, lineType, fileType,
							commitId);

					// Refresh both the inline comments and the
					// comments view tree
					refreshAfterReply(pr, client);
					return Status.OK_STATUS;
				} catch (IOException e) {
					Activator.logError(
							"Failed to post comment", e); //$NON-NLS-1$
					return new Status(IStatus.ERROR,
							Activator.PLUGIN_ID,
							"Failed to post comment: " //$NON-NLS-1$
									+ e.getMessage(),
							e);
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	/**
	 * Filters the given comments to only those that match the file paths of
	 * the currently displayed comments.
	 *
	 * @param allComments
	 *            all comments from the server
	 * @return comments matching the current file
	 */
	private List<PullRequestComment> filterCommentsForCurrentFile(
			List<PullRequestComment> allComments) {
		if (currentComments == null || currentComments.isEmpty()) {
			return allComments;
		}

		// Collect file paths from the current comments
		java.util.Set<String> paths = new java.util.HashSet<>();
		for (PullRequestComment c : currentComments) {
			if (c.getPath() != null) {
				paths.add(c.getPath());
			}
		}

		if (paths.isEmpty()) {
			return allComments;
		}

		List<PullRequestComment> filtered = new ArrayList<>();
		for (PullRequestComment c : allComments) {
			if (c.getPath() != null && paths.contains(c.getPath())) {
				filtered.add(c);
			}
		}
		return filtered;
	}

	/**
	 * Refreshes the {@link PullRequestCommentsView} with fresh comments
	 * from the server. Uses the same view-finding pattern as
	 * {@link PullRequestCommentsView} itself.
	 *
	 * @param freshComments
	 *            the fresh comments from the server
	 */
	private void refreshCommentsView(
			List<PullRequestComment> freshComments) {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench()
					.getActiveWorkbenchWindow().getActivePage();
			if (page == null) {
				return;
			}
			IViewPart part = page
					.findView(PullRequestCommentsView.VIEW_ID);
			if (part instanceof PullRequestCommentsView) {
				((PullRequestCommentsView) part)
						.updateComments(freshComments);
			}
		} catch (Exception e) {
			Activator.logError(
					"Failed to refresh comments view", e); //$NON-NLS-1$
		}
	}

	/**
	 * Retrieves the currently selected pull request from the
	 * {@link PullRequestChangedFilesView}.
	 *
	 * @return the selected pull request, or {@code null}
	 */
	private PullRequest getSelectedPullRequest() {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench()
					.getActiveWorkbenchWindow().getActivePage();
			if (page == null) {
				return null;
			}
			IViewPart part = page
					.findView(PullRequestChangedFilesView.VIEW_ID);
			if (part instanceof PullRequestChangedFilesView) {
				return ((PullRequestChangedFilesView) part)
						.getSelectedPullRequest();
			}
		} catch (Exception e) {
			Activator.logError(
					"Failed to get selected pull request", e); //$NON-NLS-1$
		}
		return null;
	}

	// ---- Lifecycle ---------------------------------------------------------

	/**
	 * Installs margin painters for creating new inline comments
	 */
	private void installMarginPainters() {
		if (leftSourceViewer != null) {
			StyledText styledText = leftSourceViewer.getTextWidget();
			if (styledText != null && !styledText.isDisposed()) {
				leftMarginPainter = new AddCommentMarginPainter(styledText,
						line -> handleNewComment(line, "FROM")); //$NON-NLS-1$
				leftMarginPainter.install();
			}
		}

		if (rightSourceViewer != null) {
			StyledText styledText = rightSourceViewer.getTextWidget();
			if (styledText != null && !styledText.isDisposed()) {
				rightMarginPainter = new AddCommentMarginPainter(styledText,
						line -> handleNewComment(line, "TO")); //$NON-NLS-1$
				rightMarginPainter.install();
			}
		}
	}

	/**
	 * Clears margin painters
	 */
	private void clearMarginPainters() {
		if (leftMarginPainter != null) {
			leftMarginPainter.uninstall();
			leftMarginPainter = null;
		}
		if (rightMarginPainter != null) {
			rightMarginPainter.uninstall();
			rightMarginPainter = null;
		}
	}

	/**
	 * Removes all inline comment painters from both panes.
	 */
	private void clearPainters() {
		if (leftPainter != null) {
			leftPainter.uninstall();
			leftPainter = null;
		}
		if (rightPainter != null) {
			rightPainter.uninstall();
			rightPainter = null;
		}
	}

	@Override
	protected void handleDispose(org.eclipse.swt.events.DisposeEvent event) {
		clearPainters();
		clearMarginPainters();
		leftSourceViewer = null;
		rightSourceViewer = null;
		currentComments = null;
		super.handleDispose(event);
	}
}
