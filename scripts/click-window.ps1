# 用相对窗口左上角的坐标点击，便于做界面人工验收（导航切换、按钮点击等）。
param(
    [string]$TitleLike = '*mpkg*',
    [int]$X = 0,
    [int]$Y = 0,
    [int]$Count = 1,
    [int]$WaitMs = 900
)

$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class DshClick {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, IntPtr dwExtraInfo);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[DshClick]::SetProcessDPIAware() | Out-Null

$proc = Get-Process -Name java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like $TitleLike } | Select-Object -First 1
if (-not $proc) { throw "找不到标题匹配 $TitleLike 的窗口" }
$h = $proc.MainWindowHandle
[DshClick]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 600

$r = New-Object DshClick+RECT
[DshClick]::GetWindowRect($h, [ref]$r) | Out-Null
$px = $r.Left + $X
$py = $r.Top + $Y
for ($i = 0; $i -lt $Count; $i++) {
    [DshClick]::SetCursorPos($px, $py) | Out-Null
    Start-Sleep -Milliseconds 220
    [DshClick]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 70
    [DshClick]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds $WaitMs
}
Write-Output "clicked window($X,$Y) -> screen($px,$py) x$Count"
