param([long]$WindowHandle, [string]$PathText, [switch]$Cancel)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class DialogAction {
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr SendMessage(IntPtr h,uint m,IntPtr w,string l);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h,uint m,IntPtr w,IntPtr l);
}
'@
$dialog=[System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$WindowHandle)
if (-not $Cancel -and $PathText) {
 $edit=$dialog.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition) | Where-Object {$_.Current.ClassName -eq 'Edit' -and $_.Current.AutomationId -eq '1148'} | Select-Object -First 1
 if (-not $edit) {throw 'File name field not found'}
 [DialogAction]::SendMessage([IntPtr]$edit.Current.NativeWindowHandle,0xC,[IntPtr]::Zero,$PathText) | Out-Null
}
$button=if ($Cancel) {2} else {1}
[DialogAction]::PostMessage([IntPtr]$WindowHandle,0x111,[IntPtr]$button,[IntPtr]::Zero) | Out-Null
