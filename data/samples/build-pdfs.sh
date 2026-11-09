#!/usr/bin/env bash
# 重新生成 data/pdfs 里的两份示例 PDF（改完 data/samples/*.html 之后跑一次）。
#
# 为什么用 Chrome 打印而不是 Java 直接写 PDF：PDFBox 要往 PDF 里嵌中文必须拿到一个可再分发的
# CJK 字体文件，微软雅黑/宋体的授权不允许进仓库。Chrome 的无头打印会把用到的字形子集嵌进 PDF，
# 中文照样能被 PDFBox 抽出来（实测两份各 2 页、零替换符，见 scratch/run_demo_pdf_probe.sh）。
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p data/pdfs

CHROME="${CHROME:-}"
if [ -z "$CHROME" ]; then
  for c in "/c/Program Files/Google/Chrome/Application/chrome.exe" \
           "/c/Program Files (x86)/Google/Chrome/Application/chrome.exe" \
           "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"; do
    [ -x "$c" ] && CHROME="$c" && break
  done
fi
[ -n "$CHROME" ] || { echo "找不到 Chrome，设一下 CHROME 环境变量再跑"; exit 1; }

TMP_PROFILE="$(mktemp -d)"
i=0
for html in data/samples/*.html; do
  i=$((i + 1))
  out="$PWD/data/pdfs/_built_$i.pdf"
  url="file://$(python -c "import urllib.parse,sys,os;print(urllib.parse.quote(os.path.abspath(sys.argv[1]).replace(os.sep,'/')))" "$html")"
  "$CHROME" --headless=new --disable-gpu --no-sandbox \
    --user-data-dir="$TMP_PROFILE" --no-pdf-header-footer \
    --print-to-pdf="$out" "$url" >/dev/null 2>&1
  name="${html##*/}"; name="${name%.html}"
  mv "$out" "data/pdfs/${name}2026.pdf"
  echo "生成 data/pdfs/${name}2026.pdf"
done
rmdir "$TMP_PROFILE" 2>/dev/null || true
