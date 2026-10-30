' start-services.vbs - Launch backend & frontend as fully detached processes.
' Using WScript.Shell.Run with window=0 detaches children from the caller session,
' so they keep running after the calling PowerShell script (and its AI terminal) exits.
Dim shell, fso, root
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)

' MCP Server FIRST: external API tools (weather/Amap) on :20811, streamable HTTP /mcp.
' The backend builds agents lazily but caches them - if adk-tools is not up before
' the first common-module chat, that agent caches 0 tools until backend restart.
shell.Run "cmd /c cd /d """ & root & "\mcp-server"" && java -jar target\adk-mcp-server-1.0.0.jar 1> """ & root & "\mcp-server-run.log"" 2> """ & root & "\mcp-server-run.err.log""", 0, False

' Backend: mvn spring-boot:run
shell.Run "cmd /c cd /d """ & root & """ && mvn -B -DskipTests spring-boot:run 1> """ & root & "\app-run.log"" 2> """ & root & "\app-run.err.log""", 0, False

' Frontend: node + vite (bypasses npm.cmd so the process is a real node.exe)
shell.Run "cmd /c cd /d """ & root & "\frontend"" && ""D:\nvm\browse\node.exe"" node_modules\vite\bin\vite.js 1> """ & root & "\frontend-run.log"" 2> """ & root & "\frontend-run.err.log""", 0, False
