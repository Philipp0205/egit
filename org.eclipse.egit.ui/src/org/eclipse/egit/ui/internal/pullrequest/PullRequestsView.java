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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.internal.bitbucket.ChangedFile;
import org.eclipse.egit.core.internal.bitbucket.PullRequest;
import org.eclipse.egit.core.internal.bitbucket.PullRequestComment;
import org.eclipse.egit.core.internal.pullrequest.IPullRequestClient;
import org.eclipse.egit.core.internal.pullrequest.PullRequestClientFactory;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIPreferences;
import org.eclipse.egit.ui.UIUtils;
import org.eclipse.egit.ui.internal.ActionUtils;
import org.eclipse.egit.ui.internal.PreferenceBasedDateFormatter;
import org.eclipse.egit.ui.internal.UIIcons;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.egit.ui.internal.commit.DiffViewer;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.DialogSettings;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.IShowInSource;
import org.eclipse.ui.part.ShowInContext;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * View for displaying Bitbucket Data Center pull requests
 */
public class PullRequestsView extends ViewPart {

	/**
	 * View ID
	 */
	public static final String VIEW_ID = "org.eclipse.egit.ui.PullRequestsView"; //$NON-NLS-1$

	private FormToolkit toolkit;

	private Form form;

	private TreeViewer pullRequestViewer;

	private PreferenceBasedDateFormatter dateFormatter;

	private ResourceManager imageCache;

	private List<PullRequest> pullRequests = new ArrayList<>();

	private Action refreshAction;

	private Action showAllPRsAction;

	private boolean showAllPRs = false; // Default: show only current user's PRs

	private String currentUsername = null;

	// View mode management
	private enum ViewMode {
		PR_LIST, CHANGES_VIEW
	}

	private ViewMode currentMode = ViewMode.PR_LIST;

	private PullRequest selectedPullRequest = null;

	private List<PullRequestChangedFile> changedFiles = new ArrayList<>();

	private List<PullRequestComment> allComments = new ArrayList<>();

	private Action backAction;

	private ISelectionChangedListener fileSelectionListener;

	private TreeColumnLayout treeColumnLayout;

	private Composite layoutComposite;

	// Split view components for changes view
	private SashForm changesSashForm;

	private SashForm rightSashForm; // Vertical split: compare viewer + comments

	private CompareViewerPane compareViewerPane;

	private Viewer compareViewer;

	private CompareConfiguration compareConfiguration;

	private TreeViewer commentsViewer;

	private Composite commentsPanel;

	private Color commentedLineHighlightColor;

	private IPropertyChangeListener editorPropertyChangeListener;

	private Job currentLoadCompareJob;

	@Override
	public void createPartControl(Composite parent) {
		dateFormatter = PreferenceBasedDateFormatter.create();
		GridLayoutFactory.fillDefaults().applyTo(parent);

		toolkit = new FormToolkit(parent.getDisplay());
		parent.addDisposeListener(e -> toolkit.dispose());

		// Initialize highlight color from Eclipse editor selection preference
		updateCommentedLineHighlightColor();
		parent.addDisposeListener(e -> {
			if (commentedLineHighlightColor != null
					&& !commentedLineHighlightColor.isDisposed()) {
				commentedLineHighlightColor.dispose();
			}
		});

		// Listen for changes to editor selection background color preference
		editorPropertyChangeListener = event -> {
			if (AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND
					.equals(event.getProperty())
					|| AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND_SYSTEM_DEFAULT
							.equals(event.getProperty())) {
				updateCommentedLineHighlightColor();
			}
		};
		EditorsUI.getPreferenceStore()
				.addPropertyChangeListener(editorPropertyChangeListener);

		form = toolkit.createForm(parent);
		form.setText("Pull Requests"); //$NON-NLS-1$
		GridDataFactory.fillDefaults().grab(true, true).applyTo(form);
		toolkit.decorateFormHeading(form);
		GridLayoutFactory.fillDefaults().applyTo(form.getBody());

		treeColumnLayout = new TreeColumnLayout();
		layoutComposite = new Composite(form.getBody(), SWT.NONE);
		layoutComposite.setLayout(treeColumnLayout);
		GridDataFactory.fillDefaults().grab(true, true)
				.applyTo(layoutComposite);

		pullRequestViewer = new TreeViewer(layoutComposite,
				SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
		pullRequestViewer.getTree().setHeaderVisible(true);
		pullRequestViewer.getTree().setLinesVisible(true);

		// ID Column
		TreeViewerColumn idColumn = createColumn(treeColumnLayout, "ID", 10, SWT.LEFT); //$NON-NLS-1$
		idColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return String.valueOf(((PullRequest) element).getId());
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Title Column
		TreeViewerColumn titleColumn = createColumn(treeColumnLayout, "Title", 40, //$NON-NLS-1$
				SWT.LEFT);
		titleColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return ((PullRequest) element).getTitle();
				}
				return ""; //$NON-NLS-1$
			}

			@Override
			public Image getImage(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if ("OPEN".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.BRANCH);
					} else if ("MERGED".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.MERGE);
					} else if ("DECLINED".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.RESET);
					}
				}
				return null;
			}
		});

		// Author Column
		TreeViewerColumn authorColumn = createColumn(treeColumnLayout, "Author", 20, //$NON-NLS-1$
				SWT.LEFT);
		authorColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if (pr.getAuthor() != null
							&& pr.getAuthor().getUser() != null) {
						return pr.getAuthor().getUser().getDisplayName();
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		// State Column
		TreeViewerColumn stateColumn = createColumn(treeColumnLayout, "State", 10, //$NON-NLS-1$
				SWT.LEFT);
		stateColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return ((PullRequest) element).getState();
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Comments Column
		TreeViewerColumn commentsColumn = createColumn(treeColumnLayout,
				"Comments", 10, SWT.LEFT); //$NON-NLS-1$
		commentsColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					int count = ((PullRequest) element).getCommentCount();
					return count > 0 ? String.valueOf(count) : ""; //$NON-NLS-1$
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Updated Column
		TreeViewerColumn updatedColumn = createColumn(treeColumnLayout, "Updated", 20, //$NON-NLS-1$
				SWT.LEFT);
		updatedColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if (pr.getUpdatedDate() != null) {
						return dateFormatter.formatDate(pr.getUpdatedDate());
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		pullRequestViewer.setContentProvider(new ITreeContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				if (inputElement instanceof List) {
					return ((List<?>) inputElement).toArray();
				}
				return new Object[0];
			}

			@Override
			public Object[] getChildren(Object parentElement) {
				return null;
			}

			@Override
			public Object getParent(Object element) {
				return null;
			}

			@Override
			public boolean hasChildren(Object element) {
				return false;
			}
		});

		pullRequestViewer.setInput(pullRequests);

		// Add double-click listener for PRs
		pullRequestViewer.addDoubleClickListener(new IDoubleClickListener() {
			@Override
			public void doubleClick(DoubleClickEvent event) {
				IStructuredSelection selection = (IStructuredSelection) event
						.getSelection();
				if (!selection.isEmpty()) {
					Object element = selection.getFirstElement();
					if (element instanceof PullRequest) {
						onPullRequestDoubleClick((PullRequest) element);
					}
				}
			}
		});

		createActions();
		contributeToActionBars();

		// Automatically refresh pull requests when view opens
		refreshPullRequests();
	}

	private TreeViewerColumn createColumn(TreeColumnLayout layout,
			String text, int weight, int style) {
		TreeViewerColumn column = new TreeViewerColumn(pullRequestViewer,
				style);
		column.getColumn().setText(text);
		layout.setColumnData(column.getColumn(), new ColumnWeightData(weight));
		return column;
	}

	private void createActions() {
		refreshAction = new Action("Refresh") { //$NON-NLS-1$
			@Override
			public void run() {
				refreshPullRequests();
			}
		};
		refreshAction.setImageDescriptor(UIIcons.ELCL16_REFRESH);
		refreshAction.setToolTipText("Refresh pull requests"); //$NON-NLS-1$

		showAllPRsAction = new Action("Show All Pull Requests", IAction.AS_CHECK_BOX) { //$NON-NLS-1$
			@Override
			public void run() {
				showAllPRs = isChecked();
				refreshPullRequests();
			}
		};
		showAllPRsAction.setChecked(false); // Default: show only PRs authored by current user
		showAllPRsAction.setToolTipText(
				"Check to show all pull requests, uncheck to show only PRs you authored (default)"); //$NON-NLS-1$

		backAction = new Action("Back to Pull Requests") { //$NON-NLS-1$
			@Override
			public void run() {
				switchToPRListView();
			}
		};
		backAction.setImageDescriptor(UIIcons.ELCL16_PREVIOUS);
		backAction.setToolTipText("Back to pull requests list"); //$NON-NLS-1$
		backAction.setEnabled(false); // Disabled by default
	}

	private void contributeToActionBars() {
		IToolBarManager toolBarManager = getViewSite().getActionBars()
				.getToolBarManager();
		toolBarManager.add(backAction);
		toolBarManager.add(refreshAction);
		toolBarManager.add(showAllPRsAction);
	}

	private void refreshPullRequests() {
		Job job = new Job("Fetching pull requests") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Fetching pull requests", //$NON-NLS-1$
						IProgressMonitor.UNKNOWN);

			try {
				// Create client using factory (supports both Bitbucket and GitHub)
				IPullRequestClient client = PullRequestClientFactory.createClient();

				if (client == null) {
					Display.getDefault().asyncExec(() -> {
						form.setText("Pull Requests - Not configured"); //$NON-NLS-1$
					});
					return Status.OK_STATUS;
				}

				// Get username from client for filtering
				final String username = client.getCurrentUser();

				// Always use the latest username from preferences
				currentUsername = username;

				// Fetch PRs - filter by author (current user) unless "Show All" is checked
				// If username is not configured, we'll show all PRs (authorFilter = null)
				String authorFilter = (showAllPRs
						|| currentUsername == null
						|| currentUsername.isEmpty()) ? null
								: currentUsername;

			List<PullRequest> fetchedPRs = client.getPullRequests(
					"OPEN", authorFilter, null, 100, 0); //$NON-NLS-1$

					final String filterInfo;
					if (showAllPRs) {
						filterInfo = "All PRs"; //$NON-NLS-1$
					} else if (currentUsername != null
							&& !currentUsername.isEmpty()) {
						filterInfo = "Authored by " + currentUsername; //$NON-NLS-1$
					} else {
						filterInfo = "All PRs (username not configured)"; //$NON-NLS-1$
					}

					Display.getDefault().asyncExec(() -> {
						if (!pullRequestViewer.getControl().isDisposed()) {
							pullRequests.clear();
							pullRequests.addAll(fetchedPRs);
							pullRequestViewer.refresh();
							form.setText(MessageFormat.format(
									"Pull Requests ({0}) - {1}", //$NON-NLS-1$
									Integer.valueOf(pullRequests.size()),
									filterInfo));
						}
					});

					return Status.OK_STATUS;
				} catch (IOException e) {
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
							"Failed to fetch pull requests", e); //$NON-NLS-1$
				} finally {
					monitor.done();
				}
			}
		};
	job.setUser(true);
	job.schedule();
}

private void onPullRequestDoubleClick(PullRequest pr) {
		selectedPullRequest = pr;

		// Fetch changed files in a background job
		Job job = new Job("Fetching changed files") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				monitor.beginTask("Fetching changed files from Bitbucket", //$NON-NLS-1$
						IProgressMonitor.UNKNOWN);

			try {
				// Create client using factory (supports both Bitbucket and GitHub)
				IPullRequestClient client = PullRequestClientFactory.createClient();

				if (client == null) {
					Activator.logError("Failed to create pull request client", null); //$NON-NLS-1$
					return Status.CANCEL_STATUS;
				}

				// Fetch changed files
				List<ChangedFile> apiChangedFiles = client.getPullRequestChanges(pr.getId());

				final List<PullRequestChangedFile> uiChangedFiles = apiChangedFiles
						.stream()
						.map(PullRequestChangedFile::fromChangedFile)
						.collect(Collectors.toList());

				// Fetch pull request activities (including comments)
				final List<PullRequestComment> comments = new ArrayList<>();
				try {
				comments.addAll(client.getPullRequestComments(pr.getId()));
				} catch (Exception e) {
					Activator.logError("Failed to fetch pull request comments", e); //$NON-NLS-1$
				}

				Display.getDefault().asyncExec(() -> {
					if (!pullRequestViewer.getControl().isDisposed()) {
						changedFiles.clear();
						changedFiles.addAll(uiChangedFiles);
						allComments.clear();
						allComments.addAll(comments);
						switchToChangesView();
					}
				});

					return Status.OK_STATUS;
				} catch (IOException e) {
					Display.getDefault().asyncExec(() -> {
						MessageDialog.openError(
								pullRequestViewer.getControl().getShell(),
								"Error", //$NON-NLS-1$
								"Failed to fetch changed files: " //$NON-NLS-1$
										+ e.getMessage());
					});
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
							"Failed to fetch changed files", e); //$NON-NLS-1$
				} finally {
					monitor.done();
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private void switchToChangesView() {
		currentMode = ViewMode.CHANGES_VIEW;

		// Dispose the existing tree viewer and layout
		if (layoutComposite != null && !layoutComposite.isDisposed()) {
			layoutComposite.dispose();
		}

		// Create a new SashForm for split view (horizontal: file tree | [compare + comments])
		changesSashForm = new SashForm(form.getBody(), SWT.HORIZONTAL);
		toolkit.adapt(changesSashForm, true, true);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(changesSashForm);

		// LEFT PANE: Create tree composite with column layout directly in sash
		treeColumnLayout = new TreeColumnLayout();
		layoutComposite = new Composite(changesSashForm, SWT.NONE);
		layoutComposite.setLayout(treeColumnLayout);

		// Recreate tree viewer
		pullRequestViewer = new TreeViewer(layoutComposite,
				SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
		pullRequestViewer.getTree().setHeaderVisible(true);
		pullRequestViewer.getTree().setLinesVisible(true);

		// Set up changed files columns
		setupChangedFilesColumns();

		// Set content provider
		pullRequestViewer.setContentProvider(new PullRequestChangesContentProvider());

		// Set input
		pullRequestViewer.setInput(changedFiles);

		// Add file selection listener
		if (fileSelectionListener == null) {
			fileSelectionListener = new ISelectionChangedListener() {
				@Override
				public void selectionChanged(SelectionChangedEvent event) {
					IStructuredSelection selection = (IStructuredSelection) event
							.getSelection();
					if (!selection.isEmpty()) {
						Object element = selection.getFirstElement();
						if (element instanceof PullRequestChangedFile) {
							onFileSelected((PullRequestChangedFile) element);
						}
					}
				}
			};
		}
		pullRequestViewer.addSelectionChangedListener(fileSelectionListener);

		// Create context menu for changed files
		createChangedFilesPopupMenu(pullRequestViewer);

		// RIGHT PANE: Create vertical SashForm for compare viewer + comments panel
		rightSashForm = new SashForm(changesSashForm, SWT.VERTICAL);
		toolkit.adapt(rightSashForm, true, true);

		// Compare viewer pane (top of right pane)
		compareViewerPane = new CompareViewerPane(rightSashForm, SWT.BORDER | SWT.FLAT);
		toolkit.adapt(compareViewerPane, true, true);
		compareViewerPane.setText("Select a file to view changes"); //$NON-NLS-1$

		// Comments panel (bottom of right pane)
		createCommentsPanel(rightSashForm);

		// Set weights for vertical split (70% compare, 30% comments)
		rightSashForm.setWeights(new int[] { 70, 30 });

		// Restore or set default sash weights for horizontal split (30% files, 70% compare+comments)
		int[] weights = restoreSashWeights();
		changesSashForm.setWeights(weights);

		// Save sash weights when changed
		changesSashForm.addListener(SWT.Resize, e -> saveSashWeights());

		// Update form title
		updateFormTitle();

		// Enable back action
		if (backAction != null) {
			backAction.setEnabled(true);
		}

		// Force layout
		form.getBody().layout(true, true);
		System.out.println("switchToChangesView() completed"); //$NON-NLS-1$

		// Auto-select first file if available
		if (!changedFiles.isEmpty()) {
			Display.getDefault().asyncExec(() -> {
				if (!pullRequestViewer.getControl().isDisposed()) {
					pullRequestViewer.getTree().select(pullRequestViewer.getTree().getItem(0));
					pullRequestViewer.setSelection(pullRequestViewer.getSelection());
				}
			});
		}
	}

	/**
	 * Creates the comments panel at the bottom of the right pane
	 *
	 * @param parent
	 *            the parent composite (rightSashForm)
	 */
	private void createCommentsPanel(Composite parent) {
		// Create tree composite with column layout
		Composite treeComposite = new Composite(parent, SWT.NONE);
		TreeColumnLayout commentColumnLayout = new TreeColumnLayout();
		treeComposite.setLayout(commentColumnLayout);

		commentsViewer = new TreeViewer(treeComposite,
				SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
		commentsViewer.getTree().setHeaderVisible(true);
		commentsViewer.getTree().setLinesVisible(true);

		// Line column
		TreeViewerColumn lineCol = new TreeViewerColumn(commentsViewer,
				SWT.LEFT);
		lineCol.getColumn().setText("Line"); //$NON-NLS-1$
		commentColumnLayout.setColumnData(lineCol.getColumn(),
				new ColumnWeightData(10));
		lineCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestComment) {
					PullRequestComment comment = (PullRequestComment) element;
					if (comment.isGeneralComment()) {
						return "General"; //$NON-NLS-1$
					} else if (comment.isFileLevelComment()) {
						return "File"; //$NON-NLS-1$
					} else if (comment.getLine() != null) {
						return String.valueOf(comment.getLine());
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Author column
		TreeViewerColumn authorCol = new TreeViewerColumn(commentsViewer,
				SWT.LEFT);
		authorCol.getColumn().setText("Author"); //$NON-NLS-1$
		commentColumnLayout.setColumnData(authorCol.getColumn(),
				new ColumnWeightData(15));
		authorCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestComment) {
					PullRequestComment comment = (PullRequestComment) element;
					return comment.getAuthorDisplayName() != null
							? comment.getAuthorDisplayName()
							: comment.getAuthorName();
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Comment column
		TreeViewerColumn commentCol = new TreeViewerColumn(commentsViewer,
				SWT.LEFT);
		commentCol.getColumn().setText("Comment"); //$NON-NLS-1$
		commentColumnLayout.setColumnData(commentCol.getColumn(),
				new ColumnWeightData(60));
		commentCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestComment) {
					PullRequestComment comment = (PullRequestComment) element;
					// Show first line of comment text
					String text = comment.getText();
					if (text != null) {
						int newlineIndex = text.indexOf('\n');
						if (newlineIndex > 0) {
							return text.substring(0, newlineIndex) + "..."; //$NON-NLS-1$
						}
						return text;
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Date column
		TreeViewerColumn dateCol = new TreeViewerColumn(commentsViewer,
				SWT.LEFT);
		dateCol.getColumn().setText("Date"); //$NON-NLS-1$
		commentColumnLayout.setColumnData(dateCol.getColumn(),
				new ColumnWeightData(15));
		dateCol.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestComment) {
					PullRequestComment comment = (PullRequestComment) element;
					if (comment.getCreatedDate() != null) {
						return dateFormatter.formatDate(comment.getCreatedDate());
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Content provider for tree (parent comments + replies)
		commentsViewer.setContentProvider(new ITreeContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				if (inputElement instanceof List) {
					return ((List<?>) inputElement).toArray();
				}
				return new Object[0];
			}

			@Override
			public Object[] getChildren(Object parentElement) {
				if (parentElement instanceof PullRequestComment) {
					List<PullRequestComment> replies = ((PullRequestComment) parentElement)
							.getReplies();
					return replies != null ? replies.toArray() : new Object[0];
				}
				return new Object[0];
			}

			@Override
			public Object getParent(Object element) {
				return null;
			}

			@Override
			public boolean hasChildren(Object element) {
				if (element instanceof PullRequestComment) {
					List<PullRequestComment> replies = ((PullRequestComment) element)
							.getReplies();
					return replies != null && !replies.isEmpty();
				}
				return false;
			}
	});

	// Set empty input initially
	commentsViewer.setInput(new ArrayList<>());

	// Add double-click listener to scroll to comment line
	commentsViewer.addDoubleClickListener(event -> {
			IStructuredSelection selection = (IStructuredSelection) event.getSelection();
			if (!selection.isEmpty()) {
				Object element = selection.getFirstElement();
				if (element instanceof PullRequestComment) {
					PullRequestComment comment = (PullRequestComment) element;
					scrollToCommentLine(comment);
				}
			}
		});
	}

	private void switchToPRListView() {
		currentMode = ViewMode.PR_LIST;

		// Save sash weights before disposal
		saveSashWeights();

		// Dispose compare viewer and configuration
		if (compareViewer != null) {
			compareViewer.getControl().dispose();
			compareViewer = null;
		}
		if (compareConfiguration != null) {
			compareConfiguration.dispose();
			compareConfiguration = null;
		}

		// Dispose compare viewer pane
		if (compareViewerPane != null && !compareViewerPane.isDisposed()) {
			compareViewerPane.dispose();
			compareViewerPane = null;
		}

		// Dispose comments panel and viewer
		if (commentsViewer != null && !commentsViewer.getControl().isDisposed()) {
			commentsViewer.getControl().dispose();
			commentsViewer = null;
		}
		if (commentsPanel != null && !commentsPanel.isDisposed()) {
			commentsPanel.dispose();
			commentsPanel = null;
		}

		// Dispose right sash form
		if (rightSashForm != null && !rightSashForm.isDisposed()) {
			rightSashForm.dispose();
			rightSashForm = null;
		}

		// Dispose sash form
		if (changesSashForm != null && !changesSashForm.isDisposed()) {
			changesSashForm.dispose();
			changesSashForm = null;
		}

		// Clear comments list
		allComments.clear();

		// Remove file selection listener
		if (fileSelectionListener != null && pullRequestViewer != null) {
			pullRequestViewer.removeSelectionChangedListener(fileSelectionListener);
		}

		// Recreate the original single-pane layout
		treeColumnLayout = new TreeColumnLayout();
		layoutComposite = new Composite(form.getBody(), SWT.NONE);
		layoutComposite.setLayout(treeColumnLayout);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(layoutComposite);

		// Recreate tree viewer
		pullRequestViewer = new TreeViewer(layoutComposite,
				SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
		pullRequestViewer.getTree().setHeaderVisible(true);
		pullRequestViewer.getTree().setLinesVisible(true);

		// Set up PR list columns
		setupPRListColumns();

		// Restore PR list content provider
		pullRequestViewer.setContentProvider(new ITreeContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				if (inputElement instanceof List) {
					return ((List<?>) inputElement).toArray();
				}
				return new Object[0];
			}

			@Override
			public Object[] getChildren(Object parentElement) {
				return null;
			}

			@Override
			public Object getParent(Object element) {
				return null;
			}

			@Override
			public boolean hasChildren(Object element) {
				return false;
			}
		});

		// Set input
		pullRequestViewer.setInput(pullRequests);

		// Re-add double-click listener for PRs
		pullRequestViewer.addDoubleClickListener(new IDoubleClickListener() {
			@Override
			public void doubleClick(DoubleClickEvent event) {
				IStructuredSelection selection = (IStructuredSelection) event
						.getSelection();
				if (!selection.isEmpty()) {
					Object element = selection.getFirstElement();
					if (element instanceof PullRequest) {
						onPullRequestDoubleClick((PullRequest) element);
					}
				}
			}
		});

		// Update form title
		updateFormTitle();

		// Disable back action
		if (backAction != null) {
			backAction.setEnabled(false);
		}

		// Force layout
		form.getBody().layout(true, true);

		// Refresh viewer
		pullRequestViewer.refresh();
	}

	private void setupChangedFilesColumns() {
		// File Column - explicitly set label provider for icons and file names
		TreeViewerColumn fileColumn = createColumn(treeColumnLayout, "File", //$NON-NLS-1$
				60, SWT.LEFT);
		fileColumn.setLabelProvider(new PullRequestChangesLabelProvider());

		// Change Type Column
		TreeViewerColumn changeColumn = createColumn(treeColumnLayout,
				"Change", 10, SWT.LEFT); //$NON-NLS-1$
		changeColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestChangedFile) {
					return ((PullRequestChangedFile) element).getChangeType()
							.toString();
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Comments Column
		TreeViewerColumn commentsColumn = createColumn(treeColumnLayout,
				"Comments", 10, SWT.CENTER); //$NON-NLS-1$
		commentsColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestChangedFile) {
					PullRequestChangedFile file = (PullRequestChangedFile) element;
					int count = getCommentCountForFile(file.getPath(), file.getSrcPath());
					return count > 0 ? String.valueOf(count) : ""; //$NON-NLS-1$
				} else if (element instanceof PullRequestFolderEntry) {
					// Show total comments for all files in folder
					PullRequestFolderEntry folder = (PullRequestFolderEntry) element;
					int count = getCommentCountForFolder(folder);
					return count > 0 ? String.valueOf(count) : ""; //$NON-NLS-1$
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Path Column
		TreeViewerColumn pathColumn = createColumn(treeColumnLayout, "Path", //$NON-NLS-1$
				30, SWT.LEFT);
		pathColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequestChangedFile) {
					return ((PullRequestChangedFile) element).getPath();
				} else if (element instanceof PullRequestFolderEntry) {
					return ((PullRequestFolderEntry) element)
							.getPath().toString();
				}
				return ""; //$NON-NLS-1$
			}
		});
	}

	private void setupPRListColumns() {
		// ID Column
		TreeViewerColumn idColumn = createColumn(treeColumnLayout, "ID", 10, //$NON-NLS-1$
				SWT.LEFT);
		idColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return String.valueOf(((PullRequest) element).getId());
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Title Column
		TreeViewerColumn titleColumn = createColumn(treeColumnLayout, "Title", //$NON-NLS-1$
				40, SWT.LEFT);
		titleColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return ((PullRequest) element).getTitle();
				}
				return ""; //$NON-NLS-1$
			}

			@Override
			public Image getImage(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if ("OPEN".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.BRANCH);
					} else if ("MERGED".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.MERGE);
					} else if ("DECLINED".equals(pr.getState())) { //$NON-NLS-1$
						return UIIcons.getImage(getImageCache(), UIIcons.RESET);
					}
				}
				return null;
			}
		});

		// Author Column
		TreeViewerColumn authorColumn = createColumn(treeColumnLayout,
				"Author", 20, SWT.LEFT); //$NON-NLS-1$
		authorColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if (pr.getAuthor() != null
							&& pr.getAuthor().getUser() != null) {
						return pr.getAuthor().getUser().getDisplayName();
					}
				}
				return ""; //$NON-NLS-1$
			}
		});

		// State Column
		TreeViewerColumn stateColumn = createColumn(treeColumnLayout, "State", //$NON-NLS-1$
				10, SWT.LEFT);
		stateColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					return ((PullRequest) element).getState();
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Comments Column
		TreeViewerColumn commentsColumn = createColumn(treeColumnLayout,
				"Comments", 10, SWT.LEFT); //$NON-NLS-1$
		commentsColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					int count = ((PullRequest) element).getCommentCount();
					return count > 0 ? String.valueOf(count) : ""; //$NON-NLS-1$
				}
				return ""; //$NON-NLS-1$
			}
		});

		// Updated Column
		TreeViewerColumn updatedColumn = createColumn(treeColumnLayout,
				"Updated", 20, SWT.LEFT); //$NON-NLS-1$
		updatedColumn.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				if (element instanceof PullRequest) {
					PullRequest pr = (PullRequest) element;
					if (pr.getUpdatedDate() != null) {
						return dateFormatter.formatDate(pr.getUpdatedDate());
					}
				}
				return ""; //$NON-NLS-1$
			}
		});
	}

	private void updateFormTitle() {
		if (currentMode == ViewMode.CHANGES_VIEW
				&& selectedPullRequest != null) {
			form.setText(MessageFormat.format(
					"Changed Files - PR #{0}: {1}", //$NON-NLS-1$
					Long.valueOf(selectedPullRequest.getId()),
					selectedPullRequest.getTitle()));
		} else {
			// Restore PR list title with filter info
			final String filterInfo;
			if (showAllPRs) {
				filterInfo = "All PRs"; //$NON-NLS-1$
			} else if (currentUsername != null && !currentUsername.isEmpty()) {
				filterInfo = "Authored by " + currentUsername; //$NON-NLS-1$
			} else {
				filterInfo = "All PRs (username not configured)"; //$NON-NLS-1$
			}
			form.setText(MessageFormat.format("Pull Requests ({0}) - {1}", //$NON-NLS-1$
					Integer.valueOf(pullRequests.size()), filterInfo));
		}
	}

	private void onFileSelected(PullRequestChangedFile file) {
		if (selectedPullRequest == null || changesSashForm == null
				|| changesSashForm.isDisposed()) {
			return;
		}

		// Cancel any pending load job
		if (currentLoadCompareJob != null) {
			currentLoadCompareJob.cancel();
		}

		// Create and set compare input in a job
		currentLoadCompareJob = new Job("Loading file comparison") { //$NON-NLS-1$
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					monitor.beginTask("Preparing comparison", IProgressMonitor.UNKNOWN); //$NON-NLS-1$

				// Check if job was cancelled
				if (monitor.isCanceled()) {
					return Status.CANCEL_STATUS;
				}

			// Get pull request client
			IPullRequestClient client = PullRequestClientFactory.createClient();

			if (client == null) {
				Activator.logError("Failed to create pull request client", null); //$NON-NLS-1$
				return Status.CANCEL_STATUS;
			}

					// Create compare configuration
					final CompareConfiguration config = PullRequestCompareEditorInput
							.createCompareConfiguration(selectedPullRequest, file);

					// Check if job was cancelled before creating compare input
					if (monitor.isCanceled()) {
						if (config != null) {
							config.dispose();
						}
						return Status.CANCEL_STATUS;
					}

					// Create compare input
					final Object compareInput = PullRequestCompareEditorInput
							.createCompareInput(client, selectedPullRequest, file, monitor);

					// Check if job was cancelled after creating compare input
					if (monitor.isCanceled()) {
						if (config != null) {
							config.dispose();
						}
						return Status.CANCEL_STATUS;
					}

					// Update UI on main thread
					Display.getDefault().asyncExec(() -> {
						// Check if this job is still the current one
						if (this != currentLoadCompareJob) {
							if (config != null) {
								config.dispose();
							}
							return;
						}

						if (changesSashForm == null
								|| changesSashForm.isDisposed()
								|| rightSashForm == null
								|| rightSashForm.isDisposed()) {
							if (config != null) {
								config.dispose();
							}
							return;
						}

						try {
							// Dispose old viewer, configuration, and pane
							// completely to avoid stale toolbar references
							if (compareViewer != null) {
								compareViewer = null;
							}
							if (compareConfiguration != null) {
								compareConfiguration.dispose();
								compareConfiguration = null;
							}
							if (compareViewerPane != null
									&& !compareViewerPane.isDisposed()) {
								compareViewerPane.dispose();
							}

							// Store new configuration
							compareConfiguration = config;

							// Create a fresh CompareViewerPane in the RIGHT sash (top of vertical split)
							compareViewerPane = new CompareViewerPane(
									rightSashForm,
									SWT.BORDER | SWT.FLAT);
							toolkit.adapt(compareViewerPane, true, true);
							compareViewerPane.setText(file.getName());

						// Create new compare viewer
						boolean useInlineComments = Activator.getDefault()
								.getPreferenceStore().getBoolean(
										UIPreferences.PULLREQUEST_SHOW_INLINE_COMMENTS);

						// Filter comments for selected file (needed before creating viewer)
						List<PullRequestComment> fileComments = allComments.stream()
								.filter(comment -> {
									if (comment.getPath() == null) {
										return false; // Skip general/file-level comments
									}
									String commentPath = comment.getPath();
									String filePath = file.getPath();
									// Handle MOVE/COPY where srcPath might be relevant
									String srcPath = file.getSrcPath();
										return commentPath.equals(filePath)
											|| (srcPath != null && commentPath.equals(srcPath));
								})
							.collect(Collectors.toList());

						InlineCommentTextMergeViewer inlineMergeViewer = null;
							System.out.println(
									"[PullRequestsView] Creating inline comment merge viewer"); //$NON-NLS-1$
						if (useInlineComments) {
							inlineMergeViewer = new InlineCommentTextMergeViewer(
									compareViewerPane,
									compareConfiguration);
							compareViewer = inlineMergeViewer;
							// CRITICAL: Set comments BEFORE setInput() so they're queued as pending
							// when updateContent() fires during setInput()
							if (!fileComments.isEmpty()) {
								inlineMergeViewer.setComments(fileComments);
							}
						} else {
							compareViewer = CompareUI
									.findContentViewer(null,
											compareInput,
											compareViewerPane,
											compareConfiguration);
						}

							if (compareViewer != null) {
								compareViewer.setInput(compareInput);
								compareViewerPane.setContent(
										compareViewer.getControl());
							}

							// Restore vertical sash weights (compare viewer + comments panel)
							rightSashForm.setWeights(new int[] { 70, 30 });

							// Restore horizontal sash weights (files + right pane)
							int[] weights = restoreSashWeights();
							changesSashForm.setWeights(weights);

							// Force layout
							rightSashForm.layout(true, true);
							changesSashForm.layout(true, true);

							// Display comments in comments panel
							if (commentsViewer != null && !commentsViewer.getControl().isDisposed()) {
							commentsViewer.setInput(fileComments);
								commentsViewer.refresh();

								// Apply line highlighting for commented lines
								// (skip when inline annotations are active
								// to avoid visual redundancy)
								if (compareViewer != null
										&& !fileComments.isEmpty()
										&& !useInlineComments) {
									highlightCommentedLines(compareViewer.getControl(), fileComments);
								}
							}
						} catch (Exception e) {
							Activator.logError("Failed to create compare viewer", e); //$NON-NLS-1$
							if (changesSashForm != null
									&& !changesSashForm.isDisposed()
									&& changesSashForm.getShell() != null) {
								MessageDialog.openError(
										changesSashForm.getShell(),
										"Error", //$NON-NLS-1$
										"Failed to display comparison: " //$NON-NLS-1$
												+ e.getMessage());
							}
						}
					});

					return Status.OK_STATUS;
				} catch (Exception e) {
					return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
							"Failed to load comparison", e); //$NON-NLS-1$
				} finally {
					monitor.done();
				}
			}
		};
		currentLoadCompareJob.setUser(false);
		currentLoadCompareJob.schedule();
	}

	@Override
	public void setFocus() {
		pullRequestViewer.getControl().setFocus();
	}

	/**
	 * Updates the highlighted color for commented lines from Eclipse editor
	 * selection background preference. Disposes the old color if it exists and
	 * creates a new one.
	 */
	private void updateCommentedLineHighlightColor() {
		// Dispose old color if it exists
		if (commentedLineHighlightColor != null
				&& !commentedLineHighlightColor.isDisposed()) {
			commentedLineHighlightColor.dispose();
		}

		// Get the selection background color from Eclipse editor preferences
		IPreferenceStore store = EditorsUI.getPreferenceStore();
		boolean useSystemDefault = store.getBoolean(
				AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND_SYSTEM_DEFAULT);

		if (useSystemDefault) {
			// Use system default selection color
			commentedLineHighlightColor = Display.getDefault()
					.getSystemColor(SWT.COLOR_LIST_SELECTION);
		} else {
			// Use custom color from preferences
			RGB rgb = PreferenceConverter.getColor(store,
					AbstractTextEditor.PREFERENCE_COLOR_SELECTION_BACKGROUND);
			commentedLineHighlightColor = new Color(rgb);
		}
	}

	@Override
	public void dispose() {
		// Cancel any pending load job
		if (currentLoadCompareJob != null) {
			currentLoadCompareJob.cancel();
		}
		if (editorPropertyChangeListener != null) {
			EditorsUI.getPreferenceStore()
					.removePropertyChangeListener(editorPropertyChangeListener);
			editorPropertyChangeListener = null;
		}
		if (compareConfiguration != null) {
			compareConfiguration.dispose();
		}
		if (imageCache != null) {
			imageCache.dispose();
		}
		if (toolkit != null) {
			toolkit.dispose();
		}
		super.dispose();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getAdapter(Class<T> adapter) {
		if (adapter == IShowInSource.class) {
			return (T) (IShowInSource) () -> getShowInContext();
		}
		return super.getAdapter(adapter);
	}

	/**
	 * Creates a ShowInContext for the current selection in changes view.
	 *
	 * @return the ShowInContext, or null if not in changes view mode
	 */
	private ShowInContext getShowInContext() {
		if (currentMode != ViewMode.CHANGES_VIEW || pullRequestViewer == null
				|| pullRequestViewer.getControl().isDisposed()) {
			return null;
		}

		IStructuredSelection selection = pullRequestViewer.getStructuredSelection();
		if (selection.isEmpty()) {
			return null;
		}

		// Collect workspace resources from selection
		List<IResource> resources = new ArrayList<>();
		for (Object element : selection.toArray()) {
			IResource resource = null;
			if (element instanceof PullRequestChangedFile) {
				resource = ((PullRequestChangedFile) element).getWorkspaceFile();
			} else if (element instanceof PullRequestFolderEntry) {
				resource = ((PullRequestFolderEntry) element).getContainer();
			}
			if (resource != null) {
				resources.add(resource);
			}
		}

		if (resources.isEmpty()) {
			return null;
		}

		return new ShowInContext(null, new StructuredSelection(resources));
	}

	private ResourceManager getImageCache() {
		if (imageCache == null) {
			imageCache = new LocalResourceManager(JFaceResources.getResources());
		}
		return imageCache;
	}

	// Helper methods for sash weight management
	private int[] restoreSashWeights() {
		IDialogSettings settings = getDialogSettings();
		String weights = settings.get(UIPreferences.PULLREQUEST_CHANGES_SASH_WEIGHTS);
		if (weights != null && !weights.isEmpty()) {
			int[] restored = stringToIntArray(weights);
			// Validate that we have exactly 2 weights for the horizontal sash
			if (restored != null && restored.length == 2) {
				return restored;
			}
		}
		return new int[] { 30, 70 }; // Default: 30% files, 70% compare
	}

	private void saveSashWeights() {
		if (changesSashForm != null && !changesSashForm.isDisposed()) {
			IDialogSettings settings = getDialogSettings();
			settings.put(UIPreferences.PULLREQUEST_CHANGES_SASH_WEIGHTS,
					intArrayToString(changesSashForm.getWeights()));
		}
	}

	private IDialogSettings getDialogSettings() {
		return DialogSettings.getOrCreateSection(
				Activator.getDefault().getDialogSettings(),
				PullRequestsView.class.getName());
	}

	private static String intArrayToString(int[] ints) {
		StringBuilder res = new StringBuilder();
		if (ints != null && ints.length > 0) {
			res.append(String.valueOf(ints[0]));
			for (int i = 1; i < ints.length; i++) {
				res.append(',');
				res.append(String.valueOf(ints[i]));
			}
		}
		return res.toString();
	}

	private static int[] stringToIntArray(String s) {
		try {
			String[] parts = s.split(","); //$NON-NLS-1$
			int[] ints = new int[parts.length];
			for (int i = 0; i < parts.length; i++) {
				ints[i] = Integer.parseInt(parts[i].trim());
			}
			return ints;
		} catch (NumberFormatException e) {
			return null; // Return null on parse error
		}
	}

	/**
	 * Highlights lines in the compare viewer that have comments.
	 * Traverses the widget tree to find StyledText widgets and applies
	 * LineBackgroundListener to highlight commented lines.
	 *
	 * @param control
	 *            the compare viewer control (root of widget tree)
	 * @param comments
	 *            list of comments for the current file
	 */
	private void highlightCommentedLines(Control control,
			List<org.eclipse.egit.core.internal.bitbucket.PullRequestComment> comments) {
		if (control == null || control.isDisposed() || comments.isEmpty()) {
			return;
		}

		// Traverse widget tree to find StyledText widgets
		if (control instanceof org.eclipse.swt.custom.StyledText) {
			org.eclipse.swt.custom.StyledText styledText = (org.eclipse.swt.custom.StyledText) control;

			// Determine if this is left or right side based on widget data or parent
			// For now, we'll check if any comments match this side
			// Create a line background listener for this StyledText
			org.eclipse.swt.custom.LineBackgroundListener listener = new org.eclipse.swt.custom.LineBackgroundListener() {
				@Override
				public void lineGetBackground(
						org.eclipse.swt.custom.LineBackgroundEvent event) {
					int lineIndex = styledText.getLineAtOffset(event.lineOffset);
					int displayLine = lineIndex + 1; // Convert to 1-based line number

					// Check if this line has a comment
					boolean hasComment = comments.stream().anyMatch(comment -> {
						if (comment.getLine() == null) {
							return false;
						}
						return comment.getLine().intValue() == displayLine;
					});

				if (hasComment) {
					// Use editor selection background color for commented lines
					event.lineBackground = commentedLineHighlightColor;
				}
				}
			};

			// Remove existing listeners to avoid duplicates
			// Note: StyledText doesn't provide a way to get existing listeners,
			// so we need to track them or recreate the widget
			styledText.addLineBackgroundListener(listener);
			styledText.redraw();
		} else if (control instanceof Composite) {
			// Recursively traverse children
			Composite composite = (Composite) control;
			for (Control child : composite.getChildren()) {
				highlightCommentedLines(child, comments);
			}
		}
	}

	/**
	 * Scrolls the compare viewer to show the line where the comment was made.
	 *
	 * @param comment
	 *            the comment to scroll to
	 */
	private void scrollToCommentLine(
			org.eclipse.egit.core.internal.bitbucket.PullRequestComment comment) {
		if (comment == null || comment.getLine() == null
				|| compareViewer == null) {
			return;
		}

		int targetLine = comment.getLine().intValue();
		String fileType = comment.getFileType(); // "FROM" (left) or "TO" (right)

		// Find the appropriate StyledText widget (left or right side)
		Control viewerControl = compareViewer.getControl();
		scrollToLineInControl(viewerControl, targetLine, fileType);
	}

	/**
	 * Recursively searches for StyledText widgets and scrolls to the target line.
	 *
	 * @param control
	 *            the control to search
	 * @param targetLine
	 *            the line number to scroll to (1-based)
	 * @param fileType
	 *            "FROM" for left side, "TO" for right side
	 */
	private void scrollToLineInControl(Control control, int targetLine,
			String fileType) {
		if (control == null || control.isDisposed()) {
			return;
		}

		if (control instanceof org.eclipse.swt.custom.StyledText) {
			org.eclipse.swt.custom.StyledText styledText = (org.eclipse.swt.custom.StyledText) control;

			// Try to determine if this is the correct side
			// This is a heuristic - in practice, the left/right determination
			// may need to be more sophisticated based on widget hierarchy
			int lineCount = styledText.getLineCount();
			if (targetLine > 0 && targetLine <= lineCount) {
				// Convert 1-based line number to 0-based index
				int lineIndex = targetLine - 1;
				int offset = styledText.getOffsetAtLine(lineIndex);

				// Set selection to this line
				styledText.setSelection(offset);
				styledText.showSelection();

				// Ensure the line is visible
				styledText.setTopIndex(Math.max(0, lineIndex - 5)); // Show some context
			}
		} else if (control instanceof Composite) {
			// Recursively search children
			Composite composite = (Composite) control;
			for (Control child : composite.getChildren()) {
				scrollToLineInControl(child, targetLine, fileType);
			}
		}
	}

	/**
	 * Counts the number of comments for a specific file.
	 *
	 * @param filePath
	 *            the file path
	 * @param srcPath
	 *            the source path (for MOVE/COPY operations)
	 * @return the number of comments (including replies)
	 */
	private int getCommentCountForFile(String filePath, String srcPath) {
		if (allComments == null || allComments.isEmpty()) {
			return 0;
		}

		return (int) allComments.stream()
				.filter(comment -> {
					if (comment.getPath() == null) {
						return false;
					}
					String commentPath = comment.getPath();
					// Match against both path and srcPath
					return commentPath.equals(filePath)
							|| (srcPath != null && commentPath.equals(srcPath));
				})
				.mapToLong(comment -> {
					// Count the comment itself plus all replies
					long count = 1;
					if (comment.getReplies() != null) {
						count += comment.getReplies().size();
					}
					return count;
				})
				.sum();
	}

	/**
	 * Counts the total number of comments for all files in a folder.
	 *
	 * @param folder
	 *            the folder entry
	 * @return the total number of comments
	 */
	private int getCommentCountForFolder(PullRequestFolderEntry folder) {
		if (allComments == null || allComments.isEmpty()) {
			return 0;
		}

		String folderPath = folder.getPath().toString();

		return (int) allComments.stream()
				.filter(comment -> {
					if (comment.getPath() == null) {
						return false;
					}
					// Check if comment path starts with folder path
					return comment.getPath().startsWith(folderPath);
				})
				.mapToLong(comment -> {
					// Count the comment itself plus all replies
					long count = 1;
					if (comment.getReplies() != null) {
						count += comment.getReplies().size();
					}
					return count;
				})
				.sum();
	}

	/**
	 * Creates a context menu for the changed files tree viewer.
	 * Follows the pattern from StagingView.createPopupMenu().
	 *
	 * @param treeViewer
	 *            the tree viewer to add the popup menu to
	 */
	private void createChangedFilesPopupMenu(final TreeViewer treeViewer) {
		final MenuManager menuMgr = new MenuManager();
		menuMgr.setRemoveAllWhenShown(true);
		Control control = treeViewer.getControl();
		control.setMenu(menuMgr.createContextMenu(control));
		menuMgr.addMenuListener(new IMenuListener() {

			@Override
			public void menuAboutToShow(IMenuManager manager) {
				control.setFocus();
				final IStructuredSelection selection = treeViewer
						.getStructuredSelection();
				if (selection.isEmpty()) {
					return;
				}

				// Collect selected files
				List<PullRequestChangedFile> selectedFiles = new ArrayList<>();
				List<PullRequestFolderEntry> selectedFolders = new ArrayList<>();
				boolean onlyFoldersSelected = true;

				for (Object element : selection.toArray()) {
					if (element instanceof PullRequestFolderEntry) {
						selectedFolders.add((PullRequestFolderEntry) element);
					} else if (element instanceof PullRequestChangedFile) {
						onlyFoldersSelected = false;
						selectedFiles.add((PullRequestChangedFile) element);
					}
				}

				// "Open in Workspace" action - only for files that exist in workspace
				if (!onlyFoldersSelected && !selectedFiles.isEmpty()) {
					Action openInWorkspaceAction = new Action(
							UIText.CommitFileDiffViewer_OpenWorkingTreeVersionInEditorMenuLabel,
							UIIcons.GOTO_INPUT) {
						@Override
						public void run() {
							openInWorkspace(selectedFiles);
						}
					};
					// Enable only if at least one file is not deleted
					boolean anyOpenable = selectedFiles.stream()
							.anyMatch(f -> f.getChangeType() != PullRequestChangedFile.ChangeType.DELETED);
					openInWorkspaceAction.setEnabled(anyOpenable);
					menuMgr.add(openInWorkspaceAction);
				}

				menuMgr.add(new Separator());

				// "Show In >" submenu
				menuMgr.add(createShowInMenu());

				// "Copy Path" action
				menuMgr.add(createCopyPathAction(treeViewer));
			}
		});
	}

	/**
	 * Creates the "Show In >" submenu.
	 *
	 * @return the show in menu contribution item
	 */
	private MenuManager createShowInMenu() {
		return UIUtils.createShowInMenu(getSite().getWorkbenchWindow());
	}

	/**
	 * Creates an action to copy the selected file/folder paths to the clipboard.
	 * Follows the pattern from StagingView.createSelectionPathCopyAction().
	 *
	 * @param viewer
	 *            the tree viewer
	 * @return the copy path action
	 */
	private IAction createCopyPathAction(final TreeViewer viewer) {
		IStructuredSelection selection = viewer.getStructuredSelection();
		String copyPathActionText = MessageFormat.format(
				UIText.StagingView_CopyPaths,
				Integer.valueOf(selection.size()));
		IAction copyAction = ActionUtils.createGlobalAction(ActionFactory.COPY,
				() -> copyPathToClipboard(viewer));
		copyAction.setText(copyPathActionText);
		return copyAction;
	}

	/**
	 * Copies the paths of the selected items to the clipboard.
	 *
	 * @param viewer
	 *            the tree viewer
	 */
	private void copyPathToClipboard(final TreeViewer viewer) {
		Clipboard cb = new Clipboard(viewer.getControl().getDisplay());
		try {
			TextTransfer t = TextTransfer.getInstance();
			String text = getPathsFromSelection(viewer.getStructuredSelection());
			if (text != null) {
				cb.setContents(new Object[] { text }, new Transfer[] { t });
			}
		} finally {
			cb.dispose();
		}
	}

	/**
	 * Gets the paths from the current selection as a string.
	 *
	 * @param selection
	 *            the current selection
	 * @return the paths as a string, or null if no valid paths
	 */
	private String getPathsFromSelection(IStructuredSelection selection) {
		Object[] selectionEntries = selection.toArray();
		if (selectionEntries.length <= 0) {
			return null;
		} else if (selectionEntries.length == 1) {
			return getPathFromElement(selectionEntries[0]);
		} else {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < selectionEntries.length; i++) {
				String text = getPathFromElement(selectionEntries[i]);
				if (text != null) {
					if (i < selectionEntries.length - 1) {
						sb.append(text).append(System.lineSeparator());
					} else {
						sb.append(text);
					}
				}
			}
			return sb.toString();
		}
	}

	/**
	 * Gets the path from a single element.
	 *
	 * @param element
	 *            the element (PullRequestChangedFile or PullRequestFolderEntry)
	 * @return the path string, or null
	 */
	private String getPathFromElement(Object element) {
		if (element instanceof PullRequestChangedFile) {
			PullRequestChangedFile file = (PullRequestChangedFile) element;
			// Return workspace location if available, otherwise repo-relative path
			IPath location = file.getLocation();
			if (location != null) {
				return location.toOSString();
			}
			return file.getPath();
		} else if (element instanceof PullRequestFolderEntry) {
			PullRequestFolderEntry folder = (PullRequestFolderEntry) element;
			// Return workspace location if available, otherwise folder path
			IPath location = folder.getLocation();
			if (location != null) {
				return location.toOSString();
			}
			return folder.getPath().toString();
		}
		return null;
	}

	/**
	 * Opens the selected changed files in the working tree editor.
	 * <p>
	 * This method follows the pattern from StagingView: it constructs the
	 * absolute filesystem path from the repository working tree and opens the
	 * file via {@link DiffViewer#openFileInEditor(java.io.File, int)}, which
	 * handles workspace file resolution and EFS fallback automatically.
	 * </p>
	 *
	 * @param files
	 *            the files to open
	 */
	private void openInWorkspace(List<PullRequestChangedFile> files) {
		for (PullRequestChangedFile file : files) {
			// Skip deleted files
			if (file.getChangeType() == PullRequestChangedFile.ChangeType.DELETED) {
				continue;
			}

			// Get repository from file (may be null if not set)
			Repository repo = file.getRepository();
			if (repo == null) {
				// Fallback: try to get workspace file and derive path from it
				IFile workspaceFile = file.getWorkspaceFile();
				if (workspaceFile != null) {
					IPath location = workspaceFile.getLocation();
					if (location != null) {
						DiffViewer.openFileInEditor(location.toFile(), -1);
					}
				}
				continue;
			}

			// Construct filesystem path from repository working tree
			java.io.File fsFile = new Path(
					repo.getWorkTree().getAbsolutePath())
					.append(file.getPath()).toFile();

			// Open file in editor (handles workspace file resolution and error reporting)
			DiffViewer.openFileInEditor(fsFile, -1);
		}
	}
}
