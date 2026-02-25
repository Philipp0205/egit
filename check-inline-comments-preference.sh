#!/bin/bash
# Diagnostic script to check inline comments preference

echo "=== Checking EGit Inline Comments Preference ==="
echo ""

# Find Eclipse workspace directories
echo "Looking for Eclipse workspace directories..."
WORKSPACE_DIRS=$(find ~ -type d -name ".metadata" 2>/dev/null | grep -v ".eclipse/egit-master")

if [ -z "$WORKSPACE_DIRS" ]; then
    echo "ERROR: No Eclipse workspace directories found"
    exit 1
fi

echo "Found workspaces:"
echo "$WORKSPACE_DIRS"
echo ""

# Check each workspace for EGit preferences
for METADATA_DIR in $WORKSPACE_DIRS; do
    PREFS_FILE="$METADATA_DIR/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.egit.ui.prefs"
    
    echo "=== Checking workspace: $(dirname "$METADATA_DIR") ==="
    
    if [ -f "$PREFS_FILE" ]; then
        echo "✓ EGit UI preferences file found: $PREFS_FILE"
        echo ""
        echo "Contents:"
        cat "$PREFS_FILE"
        echo ""
        
        # Check specific preference
        if grep -q "pullrequest_show_inline_comments=true" "$PREFS_FILE"; then
            echo "✓ INLINE COMMENTS ARE ENABLED"
        else
            echo "✗ INLINE COMMENTS ARE NOT ENABLED"
            echo "  Expected: pullrequest_show_inline_comments=true"
            echo "  To enable, add this line to: $PREFS_FILE"
        fi
        
        # Check GitHub configuration
        if grep -q "pullrequest_provider_type=GITHUB" "$PREFS_FILE"; then
            echo "✓ GitHub provider is configured"
        else
            echo "✗ GitHub provider not configured"
        fi
        
    else
        echo "✗ EGit UI preferences file not found"
        echo "  Expected location: $PREFS_FILE"
    fi
    echo ""
done

echo "=== Quick Fix ==="
echo "To enable inline comments, add this line to your workspace preferences:"
echo "pullrequest_show_inline_comments=true"
echo ""
echo "Preference file should be at:"
echo "~/.eclipse/<your-workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.egit.ui.prefs"
