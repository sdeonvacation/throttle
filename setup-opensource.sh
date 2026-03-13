#!/bin/bash

# Throttle - Open Source Setup Script
# This script initializes the git repository and prepares for GitHub push

set -e  # Exit on error

echo "🚀 Throttle - Open Source Setup"
echo "========================================"
echo ""

# Check if already a git repo
if [ -d ".git" ]; then
    echo "✅ Git repository already initialized"
else
    echo "📦 Initializing git repository..."
    git init
    echo "✅ Git repository initialized"
fi

echo ""
echo "📝 Staging all files..."
git add .

echo ""
echo "💾 Creating initial commit..."
git commit -m "Initial commit: Throttle v1.0.0-SNAPSHOT

Features:
- Core throttle with priority-based scheduling
- Resource-aware pause/resume (CPU and memory monitoring)
- Chunk-based task execution with pause checkpoints
- Anti-starvation with priority boosting
- Task termination for excessive pauses
- Queue management with configurable overflow policies
- Comprehensive simulator with 12 test scenarios
- Full documentation (HLD, architecture, contribution guidelines)

Technical:
- Java 17+ with module system
- Zero CPU idle cost (interrupt-based)
- Fully configurable thresholds and policies
- Apache License 2.0" || echo "⚠️  Commit failed (may already exist)"

echo ""
echo "✅ Local git setup complete!"
echo ""
echo "Next steps:"
echo "==========="
echo ""
echo "1. Create GitHub repository (if not already created):"
echo "   → Go to: https://github.com/sdeonvacation"
echo "   → Click 'New Repository'"
echo "   → Repository name: throttle"
echo "   → Description: Java library for adaptive task execution with resource-aware monitoring"
echo "   → Make it public"
echo "   → Do NOT initialize with README, .gitignore, or license (we already have these)"
echo ""
echo "2. Add remote and push:"
echo "   git remote add origin https://github.com/sdeonvacation/throttle.git"
echo "   git branch -M main"
echo "   git push -u origin main"
echo ""
echo "📚 Documentation files:"
echo "   - README.md (project overview)"
echo "   - CONTRIBUTING.md (contribution guidelines)"
echo "   - CODE_OF_CONDUCT.md (community guidelines)"
echo "   - SECURITY.md (security policy)"
echo "   - CHANGELOG.md (version history)"
echo "   - LICENSE (Apache 2.0)"
echo ""
echo "✨ Your project is ready for open source! ✨"
