#!/bin/bash
set -e

echo "=== PDF Background Remover - Build Script ==="

# Clean previous builds
rm -rf build dist *.spec
echo "Cleaned previous builds."

# Create venv if needed
if [ ! -d ".venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv .venv
fi

source .venv/bin/activate

# Install dependencies
echo "Installing dependencies..."
pip install -q -r requirements.txt pyinstaller

# Generate icons if needed
if [ ! -f "app.icns" ] && [ -f "logo.png" ]; then
    echo "Generating macOS icon..."
    mkdir -p icon.iconset
    python3 -c "
from PIL import Image
img = Image.open('logo.png').convert('RGBA')
for s in [16, 32, 64, 128, 256, 512]:
    img.resize((s, s), Image.LANCZOS).save(f'icon.iconset/icon_{s}x{s}.png')
    img.resize((s*2, s*2), Image.LANCZOS).save(f'icon.iconset/icon_{s}x{s}@2x.png')
"
    iconutil -c icns icon.iconset -o app.icns
    rm -rf icon.iconset
fi

if [ ! -f "app.ico" ] && [ -f "logo.png" ]; then
    echo "Generating Windows icon..."
    python3 -c "
from PIL import Image
img = Image.open('logo.png').convert('RGBA')
sizes = [(16,16), (32,32), (48,48), (64,64), (128,128), (256,256)]
imgs = [img.resize(s, Image.LANCZOS) for s in sizes]
imgs[0].save('app.ico', format='ICO', sizes=sizes, append_images=imgs[1:])
"
fi

# Build
echo "Building app..."
ICON="app.icns"
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
    ICON="app.ico"
fi

pyinstaller --onedir --windowed \
    --name "PDFBackgroundRemover" \
    --icon "$ICON" \
    --hidden-import=fitz \
    --hidden-import=fitz_old \
    --hidden-import=pymupdf \
    --hidden-import=numpy \
    --hidden-import=PIL \
    --hidden-import=webview \
    --hidden-import=flask \
    --hidden-import=jinja2 \
    --hidden-import=jinja2.ext \
    --hidden-import=werkzeug \
    --hidden-import=werkzeug.serving \
    app.py

# Patch Info.plist with file access permissions (macOS)
PLIST="dist/PDFBackgroundRemover.app/Contents/Info.plist"
if [ -f "$PLIST" ]; then
    echo "Patching Info.plist with file access permissions..."
    /usr/libexec/PlistBuddy -c "Add :NSDocumentsFolderUsageDescription string 'PDF Background Remover needs access to open and save PDF files.'" "$PLIST" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Add :NSDesktopFolderUsageDescription string 'PDF Background Remover needs access to save processed PDF files to your Desktop.'" "$PLIST" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Add :NSDownloadsFolderUsageDescription string 'PDF Background Remover needs access to save processed PDF files to Downloads.'" "$PLIST" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Add :NSFileProviderPresenceUsageDescription string 'PDF Background Remover needs file access to process PDFs.'" "$PLIST" 2>/dev/null || true
    # Re-sign after modifying plist
    codesign --force --deep --sign - "dist/PDFBackgroundRemover.app" 2>/dev/null || true
fi

echo ""
echo "=== Build complete ==="
echo "App: dist/PDFBackgroundRemover.app"
echo "Run: open dist/PDFBackgroundRemover.app"
