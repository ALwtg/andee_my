# Andee's UI Automation bridge.
#
# Reads one JSON command per line on stdin, writes one JSON answer per line on
# stdout. Started once, kept alive: a tree dump costs one process start and then
# a few milliseconds per call, where spawning PowerShell per action would cost
# ~300 ms every time and make the background screen feel broken.
#
# Commands (all with a "op"):
#   {"op":"ping"}
#   {"op":"windows"}
#   {"op":"tree","hwnd":123,"max":600}
#   {"op":"act","hwnd":123,"path":"0/2/1","action":"invoke"|"legacy"|"setvalue"|"select"|"focus","value":"..."}
#   {"op":"quit"}
#
# Elements are addressed by their path from the window's root in the Raw view —
# a list of child indices like "0/2/1". A path is cheap to re-walk and survives
# the things that break a single cached AutomationElement across processes.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# **Input too, and this is not cosmetic.** The Java side writes UTF-8; without
# this line PowerShell decodes stdin with the console code page (GBK on a Chinese
# Windows), so `find {"text":"蓝牙"}` arrives as mojibake and matches nothing —
# while ASCII commands work fine, which is exactly why it hid for so long.
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$ProgressPreference = 'SilentlyContinue'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

# **This process must be DPI-aware too.** `powershell.exe` is not, so on a scaled
# display Windows virtualises every rectangle it asks UIA for: measured here, a
# 2560x1600 screen came back as 1707x1067 and every window with it. The Java side
# works in physical pixels, so a bridge that answers in scaled ones would aim
# every tap at 2/3 of where the model pointed.
try {
    Add-Type -Namespace Andee -Name Dpi -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool SetProcessDpiAwarenessContext(System.IntPtr ctx);
'@ -ErrorAction Stop
    [Andee.Dpi]::SetProcessDpiAwarenessContext([System.IntPtr](-4)) | Out-Null
} catch { }

$walker = [System.Windows.Automation.TreeWalker]::RawViewWalker

function Write-Answer($obj) {
    $obj | ConvertTo-Json -Compress -Depth 8
    [Console]::Out.Flush()
}

# UIA reports an empty rectangle as ±infinity for some elements, and `[int]` on
# that throws — measured, and it made the whole `windows` call fail rather than
# skipping one element.
function To-I32($v) {
    $d = [double]$v
    if ([double]::IsNaN($d) -or [double]::IsInfinity($d)) { return 0 }
    if ($d -gt 2147483647) { return 2147483647 }
    if ($d -lt -2147483648) { return -2147483648 }
    return [int]$d
}

function Get-Patterns($el) {
    $names = New-Object System.Collections.ArrayList
    try { if ($el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)) { [void]$names.Add('invoke') } } catch {}
    try { if ($el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)) { [void]$names.Add('value') } } catch {}
    try { if ($el.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)) { [void]$names.Add('select') } } catch {}
    try { if ($el.GetCurrentPattern([System.Windows.Automation.LegacyIAccessiblePattern]::Pattern)) { [void]$names.Add('legacy') } } catch {}
    return $names
}

function Get-Node($el, $path, $depth) {
    $r = $el.Current.BoundingRectangle
    $node = [ordered]@{
        path    = $path
        depth   = $depth
        type    = ($el.Current.ControlType.ProgrammaticName -replace '^ControlType\.', '')
        name    = $el.Current.Name
        x       = To-I32 $r.X
        y       = To-I32 $r.Y
        w       = To-I32 $r.Width
        h       = To-I32 $r.Height
        enabled = $el.Current.IsEnabled
        patterns= @(Get-Patterns $el)
    }
    return $node
}

function Get-Tree($hwnd, $max) {
    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$hwnd)
    if (-not $root) { return $null }

    $nodes = New-Object System.Collections.ArrayList
    $stack = New-Object System.Collections.Stack
    $stack.Push(@($root, '0', 0, $root))   # element, path, depth, parent (for sibling walk)
    $index = 0
    while ($stack.Count -gt 0 -and $nodes.Count -lt $max) {
        $item = $stack.Pop()
        $el = $item[0]
        $path = $item[1]
        $depth = $item[2]

        # Descend first-child-then-sibling, so siblings are pushed in reverse and
        # pop in document order.
        $children = New-Object System.Collections.ArrayList
        $child = $walker.GetFirstChild($el)
        $i = 0
        while ($child -ne $null -and $i -lt 200) {
            [void]$children.Add($child)
            $child = $walker.GetNextSibling($child)
            $i++
        }
        for ($k = $children.Count - 1; $k -ge 0; $k--) {
            $stack.Push(@($children[$k], "$path/$k", $depth + 1, $el))
        }

        if ($el.Current.IsOffscreen) { continue }
        $r = $el.Current.BoundingRectangle
        if ($r.Width -le 0 -or $r.Height -le 0) { continue }
        [void]$nodes.Add((Get-Node $el $path $depth))
    }
    , $nodes
}

function Resolve-Node($hwnd, $path) {
    $parts = $path.Split('/')
    $el = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$hwnd)
    for ($i = 1; $i -lt $parts.Count; $i++) {
        $want = [int]$parts[$i]
        $child = $walker.GetFirstChild($el)
        $k = 0
        while ($child -ne $null -and $k -lt $want) {
            $child = $walker.GetNextSibling($child)
            $k++
        }
        if ($child -eq $null) { return $null }
        $el = $child
    }
    return $el
}

function Invoke-Action($hwnd, $path, $action, $value) {
    $el = Resolve-Node $hwnd $path
    if ($el -eq $null) { return @{ ok = $false; error = "element not found at $path" } }
    switch ($action) {
        'invoke' {
            $p = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
            $p.Invoke()
            return @{ ok = $true; how = 'invoke' }
        }
        'legacy' {
            $p = $el.GetCurrentPattern([System.Windows.Automation.LegacyIAccessiblePattern]::Pattern)
            $p.DoDefaultAction()
            return @{ ok = $true; how = 'legacy' }
        }
        'setvalue' {
            $p = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
            $p.SetValue([string]$value)
            return @{ ok = $true; how = 'value' }
        }
        'select' {
            $p = $el.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            $p.Select()
            return @{ ok = $true; how = 'select' }
        }
        'focus' {
            $el.SetFocus()
            return @{ ok = $true; how = 'focus' }
        }
        default { return @{ ok = $false; error = "unknown action $action" } }
    }
}

# Find elements whose name (or AutomationId) matches $text.
#
# Two passes, because they cost very different amounts:
#   1. **exact** — one `FindAll` with a property condition. That is a single
#      cross-process query, typically 20-80 ms, and it is what "tap 发送" should
#      pay in the common case where the app names its button exactly that.
#   2. **contains** — a walk, because UIA property conditions cannot express
#      "contains". Capped at 1500 visited nodes and 40 hits: the point of this op
#      is to answer quickly, and a caller that matches 40 things has a word that is
#      too common rather than a screen that needs more searching.
#
# `path` comes back so the caller can act without a second lookup.
function Find-Elements($hwnd, $text, $exact) {
    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$hwnd)
    if (-not $root) { return $null }
    $needle = $text.Trim()
    if ($needle -eq '') { return , (New-Object System.Collections.ArrayList) }

    $hits = New-Object System.Collections.ArrayList

    if ($exact) {
        $cond = New-Object System.Windows.Automation.OrCondition(
            (New-Object System.Windows.Automation.PropertyCondition(
                [System.Windows.Automation.AutomationElement]::NameProperty, $needle)),
            (New-Object System.Windows.Automation.PropertyCondition(
                [System.Windows.Automation.AutomationElement]::AutomationIdProperty, $needle)))
        $found = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $cond)
        $i = 0
        foreach ($el in $found) {
            if ($i -ge 40) { break }
            $node = Get-Node $el '' 0
            $node['score'] = 1.0
            [void]$hits.Add($node)
            $i++
        }
        return , $hits
    }

    $lower = $needle.ToLower()
    $stack = New-Object System.Collections.Stack
    $stack.Push(@($root, ''))
    $visited = 0
    while ($stack.Count -gt 0 -and $hits.Count -lt 40 -and $visited -lt 1500) {
        $item = $stack.Pop()
        $el = $item[0]
        $path = $item[1]
        $visited++
        $name = [string]$el.Current.Name
        $aid = [string]$el.Current.AutomationId
        $ln = $name.ToLower()
        $la = $aid.ToLower()
        if (($ln -ne '' -and $ln.Contains($lower)) -or ($la -ne '' -and $la.Contains($lower))) {
            $node = Get-Node $el $path 0
            $node['score'] = if ($ln -eq $lower) { 1.0 } elseif ($ln.StartsWith($lower)) { 0.85 } else { 0.7 }
            [void]$hits.Add($node)
            continue
        }
        $kids = New-Object System.Collections.ArrayList
        $child = $walker.GetFirstChild($el)
        $k = 0
        while ($child -ne $null -and $k -lt 200) { [void]$kids.Add($child); $child = $walker.GetNextSibling($child); $k++ }
        # Same path spelling as `tree` — "1/1/1/2/0/1", never "/1/…". A caller that
        # found a node with `find` and then acts on it resolves the path through
        # `Resolve-Node`; two spellings for one node is the kind of difference that
        # only shows up as "element not found" on a screen that plainly has it.
        for ($j = $kids.Count - 1; $j -ge 0; $j--) {
            $child0 = if ($path -eq '') { "$j" } else { "$path/$j" }
            $stack.Push(@($kids[$j], $child0))
        }
    }
    return , $hits
}

Write-Answer @{ ok = $true; hello = 'uia-bridge'; pid = $PID }

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($line -eq $null) { break }
    $line = $line.Trim()
    if ($line -eq '') { continue }
    try {
        $cmd = $line | ConvertFrom-Json
        switch ($cmd.op) {
            'ping' { Write-Answer @{ ok = $true; pong = $true } }
            'quit' { Write-Answer @{ ok = $true; bye = $true }; break }
            'windows' {
                $found = New-Object System.Collections.ArrayList
                $root = [System.Windows.Automation.AutomationElement]::RootElement
                $cond = [System.Windows.Automation.Condition]::TrueCondition
                $kids = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $cond)
                foreach ($k in $kids) {
                    $r = $k.Current.BoundingRectangle
                    $w = To-I32 $r.Width
                    $h = To-I32 $r.Height
                    if ($w -le 0 -or $h -le 0) { continue }
                    [void]$found.Add([ordered]@{
                        hwnd  = $k.Current.NativeWindowHandle
                        pid   = $k.Current.ProcessId
                        name  = $k.Current.Name
                        type  = ($k.Current.ControlType.ProgrammaticName -replace '^ControlType\.', '')
                        x = To-I32 $r.X; y = To-I32 $r.Y; w = $w; h = $h
                    })
                }
                Write-Answer @{ ok = $true; windows = @($found) }
            }
            'tree' {
                $max = if ($cmd.max) { [int]$cmd.max } else { 600 }
                $nodes = Get-Tree ([int]$cmd.hwnd) $max
                if ($nodes -eq $null) { Write-Answer @{ ok = $false; error = 'window not found' } }
                else { Write-Answer @{ ok = $true; nodes = @($nodes) } }
            }
            'act' {
                $r = Invoke-Action ([int]$cmd.hwnd) ([string]$cmd.path) ([string]$cmd.action) ([string]$cmd.value)
                Write-Answer $r
            }
            'find' {
                $hits = Find-Elements ([int]$cmd.hwnd) ([string]$cmd.text) ([bool]$cmd.exact)
                if ($hits -eq $null) { Write-Answer @{ ok = $false; error = 'window not found' } }
                else { Write-Answer @{ ok = $true; nodes = @($hits) } }
            }
            default { Write-Answer @{ ok = $false; error = "unknown op $($cmd.op)" } }
        }
        if ($cmd.op -eq 'quit') { break }
    } catch {
        Write-Answer @{ ok = $false; error = $_.Exception.Message }
    }
}
