Add-Type -AssemblyName System.Drawing
$p = 'C:\Users\16039\WorkBuddy\记账APP\dist\fs-6.png'
$img = [System.Drawing.Image]::FromFile($p)
"{0}x{1}" -f $img.Width, $img.Height
$img.Dispose()
