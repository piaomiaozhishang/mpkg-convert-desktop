# 截取正在运行的应用窗口（用于 UI 人工验收留证）。
#
# 两个必须处理的坑：
# 1. 先把本进程标记为 DPI 感知，否则在高缩放显示器上 GetWindowRect 返回虚拟化的
#    逻辑坐标，截图会和窗口错位；
# 2. 用 PrintWindow 直接向窗口取图，而不是抓屏——抓屏会被前台窗口遮挡，
#    而 SetForegroundWindow 在后台进程里经常被系统拒绝。
param(
    [string]$TitleLike = '*mpkg*',
    [string]$ProcessName = 'java',
    [long]$WindowHandle = 0,
    [string]$Out = 'out\app-window.png',
    [int]$MaxHeight = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class DshWin32 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int X, int Y, int cx, int cy, uint flags);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[DshWin32]::SetProcessDPIAware() | Out-Null

$HWND_TOPMOST = [IntPtr](-1)
$HWND_NOTOPMOST = [IntPtr](-2)
$SWP_NOMOVE = 0x0002
$SWP_NOSIZE = 0x0001
$SWP_SHOWWINDOW = 0x0040

$proc = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like $TitleLike } |
    Select-Object -First 1
if (-not $proc -and $WindowHandle -eq 0) { throw "找不到标题匹配 $TitleLike 的窗口" }

$h = if ($WindowHandle -ne 0) { [IntPtr]$WindowHandle } else { $proc.MainWindowHandle }
[DshWin32]::ShowWindow($h, 9) | Out-Null   # SW_RESTORE

if ($MaxHeight -gt 0) {
    $r0 = New-Object DshWin32+RECT
    [DshWin32]::GetWindowRect($h, [ref]$r0) | Out-Null
    $w0 = $r0.Right - $r0.Left
    $h0 = $r0.Bottom - $r0.Top
    if ($h0 -gt $MaxHeight) {
        [DshWin32]::MoveWindow($h, $r0.Left, [Math]::Max(0, $r0.Top), $w0, $MaxHeight, $true) | Out-Null
        Start-Sleep -Milliseconds 800
    }
}

# 置顶再取消置顶，双保险把窗口提到前面（PrintWindow 本身不依赖前台状态）。
[DshWin32]::SetWindowPos($h, $HWND_TOPMOST, 0, 0, 0, 0, $SWP_NOMOVE -bor $SWP_NOSIZE -bor $SWP_SHOWWINDOW) | Out-Null
[DshWin32]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 900
[DshWin32]::SetWindowPos($h, $HWND_NOTOPMOST, 0, 0, 0, 0, $SWP_NOMOVE -bor $SWP_NOSIZE -bor $SWP_SHOWWINDOW) | Out-Null
Start-Sleep -Milliseconds 500

$r = New-Object DshWin32+RECT
[DshWin32]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$ht = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $ht
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [DshWin32]::PrintWindow($h, $hdc, 2)   # PW_RENDERFULLCONTENT
$g.ReleaseHdc($hdc)
if (-not $ok) {
    Write-Output "PrintWindow 返回 false，退回抓屏"
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, (New-Object System.Drawing.Size $w, $ht))
}
$full = Join-Path (Get-Location) $Out
$dir = Split-Path $full -Parent
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved $full  size=${w}x${ht}  printWindow=$ok  bytes=$((Get-Item $full).Length)"
