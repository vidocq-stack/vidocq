@REM
@REM Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
@REM
@REM This program and the accompanying materials are made available under the
@REM terms of the Eclipse Public License 2.0 which is available at
@REM https://www.eclipse.org/legal/epl-2.0/
@REM
@REM This Source Code may also be made available under the following Secondary
@REM Licenses when the conditions for such availability set forth in the Eclipse
@REM Public License, v. 2.0 are satisfied: GNU General Public License, version 2
@REM or any later version, which is available at
@REM https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
@REM
@REM It is also made available under the European Union Public Licence v. 1.2,
@REM which is available at
@REM https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
@REM
@REM SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
@REM

@echo off
rem Vidocq CLI launcher — expects the sibling ..\modules directory from the cli.zip layout.
setlocal
set "DIR=%~dp0.."
java --module-path "%DIR%\modules" -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli %*
endlocal
