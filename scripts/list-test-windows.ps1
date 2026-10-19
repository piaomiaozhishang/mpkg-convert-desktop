param([int]$TargetPid)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class TestWindows {
 public delegate bool Callback(IntPtr h, IntPtr p);
 [DllImport("user32.dll")] public static extern bool EnumWindows(Callback c, IntPtr p);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder b,int n);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder b,int n);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
}
'@
[TestWindows]::EnumWindows({param($handle,$unused)
 $ownerPid=0
 [TestWindows]::GetWindowThreadProcessId($handle,[ref]$ownerPid) | Out-Null
 if ($ownerPid -eq $TargetPid) {
  $title=New-Object Text.StringBuilder 512
  $class=New-Object Text.StringBuilder 512
  [TestWindows]::GetWindowText($handle,$title,512) | Out-Null
  [TestWindows]::GetClassName($handle,$class,512) | Out-Null
  Write-Host "$handle visible=$([TestWindows]::IsWindowVisible($handle)) class=$class title=$title"
 }
 return $true
},[IntPtr]::Zero) | Out-Null
