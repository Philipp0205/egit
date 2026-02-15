# Debug Test Instructions for PR Files Issue

## Status
✅ Debug logging has been added to both:
- `org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubClient.java`
- `org.eclipse.egit.ui/src/org/eclipse/egit/ui/internal/pullrequest/PullRequestChangedFilesView.java`

✅ Code is compiled and ready to test

## Testing Steps

### 1. Restart Eclipse
- **Completely quit Eclipse** (not just close workspace)
- Wait 5 seconds
- **Start Eclipse again**

### 2. Open Required Views
Before testing, make sure these views are visible:

- **Console view**: Window → Show View → Console
- **Error Log view**: Window → Show View → Error Log  
- **Progress view**: Window → Show View → Progress

### 3. Clear Logs
- Right-click in **Error Log** → Delete All
- Clear **Console** if needed

### 4. Test PR #3
1. Open **Pull Requests List** view
2. Select **PR #3** (Philipp0205/egit)
3. The **Pull Request Changed Files** view should update
4. Watch the **Progress** view for "Fetching changed files" job

### 5. Check Output

#### Expected Console Output (System.err):
```
=== UI DEBUG: Job started for PR 3
=== UI DEBUG: Client created: org.eclipse.egit.core.internal.github.GitHubClient
=== UI DEBUG: About to call getPullRequestChanges()
=== DEBUG: getPullRequestChanges() CALLED for PR 3
=== DEBUG: path = /repos/Philipp0205/egit/pulls/3/files?per_page=100
=== DEBUG: About to call doGetAllPages()
=== DEBUG: doGetAllPages() returned 1 pages
=== DEBUG: Page 1 content: [{"sha":"...
=== DEBUG: Parsed 4 files from page 1
=== DEBUG: RETURNING 4 total files
=== UI DEBUG: getPullRequestChanges() returned 4 files
=== UI DEBUG: Converted to 4 UI files
=== UI DEBUG: About to fetch comments
=== UI DEBUG: Fetched 0 comments
=== UI DEBUG: About to update UI with 4 files and 0 comments
=== UI DEBUG: Inside UI thread, updating viewer
=== UI DEBUG: Added 4 files to viewer
=== UI DEBUG: Viewer refreshed and title updated
=== UI DEBUG: Job completed successfully
```

#### Expected Error Log Output (Activator.logInfo):
```
GitHubClient: Fetching changed files for PR 3
GitHubClient.doGetAllPages: Starting to fetch https://api.github.com/repos/Philipp0205/egit/pulls/3/files?per_page=100
GitHubClient.doGetAllPages: Page 1 received (XXXX chars)
GitHubClient.doGetAllPages: No more pages (total: 1)
GitHubClient: Received 1 page(s) of changed files
GitHubClient: Parsing page 1 (length=XXXX chars)
GitHubClient: Found 4 files in page 1
GitHubClient: Total changed files: 4
GitHubJsonParser.parseChangedFiles: Parsing JSON (length=XXXX)
GitHubJsonParser.parseChangedFiles: Parsed 4 files
```

### 6. What to Look For

**If the method is NOT being called at all:**
- Console will NOT show: `=== DEBUG: getPullRequestChanges() CALLED`
- Only shows: `=== UI DEBUG: About to fetch comments`
- **This means:** Exception thrown before the method or method not called

**If the method IS called but returns empty:**
- Console shows: `=== DEBUG: RETURNING 0 total files`
- **This means:** JSON parsing failed

**If files are fetched but UI doesn't update:**
- Console shows: `=== UI DEBUG: Added X files to viewer`
- But viewer is still empty
- **This means:** UI refresh problem

**If job hangs forever:**
- Console shows debug messages up to a certain point, then stops
- **This means:** Hanging at that specific operation

## Next Steps After Testing

### Report Back:
1. **Full Console output** (all lines starting with `===`)
2. **Full Error Log output** (all GitHubClient/GitHubJsonParser entries)
3. **What the UI shows** (empty tree, error dialog, files displayed?)
4. **Job status** (completed, failed, hanging?)

### Files Modified (for reference):
- `org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubClient.java`
- `org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubJsonParser.java`  
- `org.eclipse.egit.ui/src/org/eclipse/egit/ui/internal/pullrequest/PullRequestChangedFilesView.java`

## Troubleshooting

**If you don't see ANY debug output in Console:**
- Eclipse might be running old code
- Try: Project → Clean → Clean all projects → OK
- Restart Eclipse again

**If Console view doesn't show anything:**
- Make sure Console view is set to show the right console
- Check dropdown in Console view toolbar
- Should show "Eclipse Application" console

**If you see an IOException with stack trace:**
- Good! Copy the FULL stack trace
- This tells us exactly what's failing
