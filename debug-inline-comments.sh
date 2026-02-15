#!/bin/bash
# Debug script for inline comments feature

echo "=== Inline Comments Debug Diagnostics ==="
echo ""

echo "1. Checking if code changes are present:"
echo "   - PullRequestClientFactory imports in PullRequestsView:"
grep -c "PullRequestClientFactory" org.eclipse.egit.ui/src/org/eclipse/egit/ui/internal/pullrequest/PullRequestsView.java
echo "   - createClient() calls in PullRequestsView:"
grep -c "PullRequestClientFactory.createClient()" org.eclipse.egit.ui/src/org/eclipse/egit/ui/internal/pullrequest/PullRequestsView.java
echo "   - setComments BEFORE setInput timing fix:"
grep -B 5 "setInput(compareInput)" org.eclipse.egit.ui/src/org/eclipse/egit/ui/internal/pullrequest/PullRequestsView.java | grep -c "setComments"
echo "   - String boundary tracking in JSON parser:"
grep -c "boolean inString = false" org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubJsonParser.java
echo "   - original_line fallback:"
grep -c "original_line" org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubJsonParser.java
echo ""

echo "2. Check preferences (from Eclipse workspace):"
echo "   Location: ~/.eclipse/<workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.egit.ui.prefs"
echo "   You should have:"
echo "     - pullrequest_provider_type=GITHUB"
echo "     - github_owner=<your-value>"
echo "     - github_repo=<your-value>"
echo "     - github_access_token=<your-value>"
echo "     - pullrequest_show_inline_comments=true"
echo ""

echo "3. Things to check in Eclipse console/logs:"
echo "   a) When refreshing PRs, look for:"
echo "      'PullRequestClientFactory: config is null or providerType is null'"
echo "      'PullRequestClientFactory: createClient returned null for provider: GITHUB'"
echo ""
echo "   b) When fetching PR details, look for:"
echo "      'Fetching comments for PR #<number>'"
echo "      'Fetched <N> comments'"
echo ""
echo "   c) When selecting a file, check if InlineCommentTextMergeViewer was created:"
echo "      Should see log messages about setComments() being called"
echo ""

echo "4. Debug via System.out.println additions:"
echo "   Add these to PullRequestsView.onFileSelected() around line 1274:"
echo "     System.out.println(\"useInlineComments: \" + useInlineComments);"
echo "     System.out.println(\"fileComments.size(): \" + fileComments.size());"
echo "     System.out.println(\"About to call setComments before setInput\");"
echo ""

echo "5. Check InlineCommentTextMergeViewer:"
echo "   Add debug output to setComments() method to verify it's being called:"
echo "     System.out.println(\"setComments called with \" + comments.size() + \" comments\");"
echo ""

echo "6. Check InlineCommentPainter:"
echo "   Add debug output to applyComments() method:"
echo "     System.out.println(\"applyComments called with \" + comments.size() + \" comments\");"
echo ""

echo "=== End Diagnostics ==="
