"""Applies the folder-picker hooks to the game's LocalGameBridge.smali (baksmali output). usage: patch_smali.py <file>"""
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
N = 0
def sub(pat, rep, count_expected):
    global s, N
    s, n = re.subn(pat, rep, s)
    assert n == count_expected, (pat, n)
    N += n
# 1. export / import / log-file folder
sub(r'invoke-static \{(v\d+|p\d+)\}, Landroid/os/Environment;->getExternalStoragePublicDirectory\(Ljava/lang/String;\)Ljava/io/File;',
    r'invoke-static {\1}, Lcom/ffbeforever/patch/ExportDir;->dir(Ljava/lang/String;)Ljava/io/File;', 3)
# 2. MediaStore relative path for the log export
sub(r'(const-string v0, "relative_path"\n\n    sget-object v2, Landroid/os/Environment;->DIRECTORY_DOWNLOADS:Ljava/lang/String;\n)',
    r'\1\n    invoke-static {v2}, Lcom/ffbeforever/patch/ExportDir;->rel(Ljava/lang/String;)Ljava/lang/String;\n\n    move-result-object v2\n', 1)
# 3. second page bridge next to FFBEBridge
sub(r'(invoke-virtual \{v12, v3, v4\}, Landroid/webkit/WebView;->addJavascriptInterface\(Ljava/lang/Object;Ljava/lang/String;\)V\n)',
    r'''\1
    new-instance v3, Lcom/ffbeforever/patch/ExportBridge;

    invoke-direct {v3, v1}, Lcom/ffbeforever/patch/ExportBridge;-><init>(Landroid/content/Context;)V

    const-string v4, "FFBEExport"

    invoke-virtual {v12, v3, v4}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V
''', 1)
# 4. page text gets the folder card
sub(r'(\n    const-string v13, "http://127\.0\.0\.1/"\n)',
    r'''
    invoke-static {v14}, Lcom/ffbeforever/patch/ExportBridge;->page(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v14
\1''', 1)
open(p, 'w', encoding='utf-8').write(s)
print('applied', N, 'edits')
