#!/bin/bash
echo "=== EGit GitHub Integration Setup Verification ==="
echo ""

echo "1. Checking MANIFEST.MF exports..."
if grep -q "org.eclipse.egit.core.internal.github" org.eclipse.egit.core/META-INF/MANIFEST.MF; then
    echo "   ✓ github package exported"
else
    echo "   ✗ github package NOT exported"
fi

if grep -q "org.eclipse.egit.core.internal.pullrequest" org.eclipse.egit.core/META-INF/MANIFEST.MF; then
    echo "   ✓ pullrequest package exported"
else
    echo "   ✗ pullrequest package NOT exported"
fi

echo ""
echo "2. Checking source files exist..."
files=(
    "org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubClient.java"
    "org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubDeviceFlowAuth.java"
    "org.eclipse.egit.core/src/org/eclipse/egit/core/internal/github/GitHubJsonParser.java"
    "org.eclipse.egit.core/src/org/eclipse/egit/core/internal/pullrequest/IPullRequestClient.java"
    "org.eclipse.egit.core/src/org/eclipse/egit/core/internal/pullrequest/PullRequestClientFactory.java"
)

for file in "${files[@]}"; do
    if [ -f "$file" ]; then
        echo "   ✓ $(basename $file)"
    else
        echo "   ✗ $(basename $file) MISSING"
    fi
done

echo ""
echo "3. Checking compiled binaries..."
if [ -d "org.eclipse.egit.core/bin" ] || [ -d "org.eclipse.egit.core/target" ]; then
    echo "   ⚠ Binary folders exist (this is OK after rebuild)"
else
    echo "   ✓ No stale binaries"
fi

echo ""
echo "=== Next Steps ==="
echo "1. Rebuild: mvn clean install -DskipTests"
echo "2. Restart Eclipse completely"
echo "3. Test: Window → Preferences → Team → Pull Requests"
echo "4. Select 'GitHub' and try OAuth Device Flow"
