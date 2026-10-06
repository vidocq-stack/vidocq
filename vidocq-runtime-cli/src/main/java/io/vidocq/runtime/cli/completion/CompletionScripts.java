/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.cli.completion;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure generators for shell completion scripts, built from {@link CompletionSpec}.
 *
 * <p>Both scripts share {@code _vidocq_spec}, a POSIX function that receives the words
 * typed after {@code vidocq} (the one being completed excluded) and prints the words to
 * offer — the shell port of {@link CompletionSpec#candidates(List)}. {@code @}-words are
 * then expanded by the shell-specific function: files through the shell's own completion,
 * config keys from {@code vidocq.properties}, and extension ids from {@code pom.xml}.
 * No JVM starts on TAB.</p>
 */
public final class CompletionScripts {

    private CompletionScripts() {}

    /** Emit the completion script for {@code shell}; {@code commands} are the top-level ones. */
    public static String script(Shell shell, List<String> commands) {
        return switch (shell) {
            case BASH -> bash(commands);
            case ZSH -> zsh(commands);
        };
    }

    static String bash(List<String> commands) {
        return """
                # vidocq bash completion
                # Install: vidocq completion install bash
                #      or: source <(vidocq completion bash)
                %s
                _vidocq() {
                    local cur="${COMP_WORDS[COMP_CWORD]}" w words=""
                    for w in $(_vidocq_spec "${COMP_WORDS[@]:1:COMP_CWORD-1}"); do
                        case "$w" in
                            @files) COMPREPLY=( $(compgen -f -- "$cur") ); return 0 ;;
                            @*)     words="$words $(_vidocq_dynamic "$w")" ;;
                            *)      words="$words $w" ;;
                        esac
                    done
                    COMPREPLY=( $(compgen -W "$words" -- "$cur") )
                    return 0
                }
                complete -F _vidocq vidocq
                """.formatted(shared(commands));
    }

    static String zsh(List<String> commands) {
        return """
                #compdef vidocq
                # vidocq zsh completion
                # Install: vidocq completion install zsh
                #      or: source <(vidocq completion zsh)
                %s
                _vidocq() {
                    local w
                    local -a cands
                    for w in ${=$(_vidocq_spec "${(@)words[2,CURRENT-1]}")}; do
                        case "$w" in
                            @files) _files; return ;;
                            @*)     cands+=( ${(f)"$(_vidocq_dynamic "$w")"} ) ;;
                            *)      cands+=( "$w" ) ;;
                        esac
                    done
                    compadd -a cands
                }
                (( $+functions[compdef] )) || { autoload -Uz compinit && compinit; }
                compdef _vidocq vidocq
                """.formatted(shared(commands));
    }

    /** The POSIX functions both scripts share. */
    private static String shared(List<String> commands) {
        StringBuilder sh = new StringBuilder();
        sh.append("_vidocq_spec() {\n");
        sh.append("    if [ $# -eq 0 ]; then echo \"").append(join(commands)).append("\"; return; fi\n");
        sh.append("    local cmd=\"$1\" ctx sub prev pos\n");
        sh.append("    case \"$cmd\" in\n");
        aliases(sh, CompletionSpec.COMMAND_ALIASES, "cmd");
        sh.append("    esac\n");
        sh.append("    ctx=\"$cmd\"; pos=$#\n");
        sh.append("    case \"$cmd\" in\n");
        sh.append("        ").append(String.join("|", CompletionSpec.SUBCOMMANDS.keySet())).append(")\n");
        sh.append("            if [ $# -eq 1 ]; then\n");
        sh.append("                case \"$cmd\" in\n");
        CompletionSpec.SUBCOMMANDS.forEach((cmd, subs) ->
                sh.append("                    ").append(cmd).append(") echo \"").append(join(subs)).append("\" ;;\n"));
        sh.append("                esac\n");
        sh.append("                return\n");
        sh.append("            fi\n");
        sh.append("            sub=\"$2\"\n");
        sh.append("            case \"$sub\" in\n");
        aliases(sh.append("    "), CompletionSpec.SUBCOMMAND_ALIASES, "sub");
        sh.append("            esac\n");
        sh.append("            ctx=\"$cmd $sub\"; pos=$(($# - 1)) ;;\n");
        sh.append("    esac\n");
        sh.append("    eval \"prev=\\${$#}\"\n");
        sh.append("    case \"$ctx $prev\" in\n");
        cases(sh, CompletionSpec.OPTION_VALUES, "; return");
        sh.append("    esac\n");
        sh.append("    case \"$prev\" in\n");
        sh.append("        ").append(String.join("|", CompletionSpec.VALUE_OPTIONS.stream().sorted().toList()))
                .append(") return ;;\n");
        sh.append("    esac\n");
        sh.append("    if [ \"$pos\" -eq 1 ]; then\n");
        sh.append("        case \"$ctx\" in\n");
        CompletionSpec.FIRST_ARGUMENTS.forEach((ctx, words) ->
                sh.append("            \"").append(ctx).append("\") echo \"").append(join(words)).append("\" ;;\n"));
        sh.append("        esac\n");
        sh.append("    fi\n");
        sh.append("    case \"$ctx\" in\n");
        cases(sh, CompletionSpec.ARGUMENTS, "");
        sh.append("    esac\n");
        sh.append("}\n");
        sh.append("_vidocq_addable() {\n");
        sh.append("    local taken id\n");
        sh.append("    taken=\" $(_vidocq_dynamic @pom-extensions | tr '\\n' ' ') $* \"\n");
        sh.append("    for id in ").append(join(CompletionSpec.extensionIds())).append("; do\n");
        sh.append("        case \"$taken\" in *\" $id \"*) ;; *) echo \"$id\" ;; esac\n");
        sh.append("    done\n");
        sh.append("}\n");
        sh.append("""
                _vidocq_dynamic() {
                    case "$1" in
                        @config-keys)
                            for f in vidocq.properties src/main/resources/vidocq.properties; do
                                [ -f "$f" ] && sed -n 's/^[[:space:]]*\\([^#![:space:]=:][^[:space:]=:]*\\)[[:space:]]*[=:].*/\\1/p' "$f"
                            done ;;
                        @pom-extensions)
                            [ -f pom.xml ] && sed -n 's/.*<artifactId>vidocq-runtime-\\(.*\\)-extension<\\/artifactId>.*/\\1/p' pom.xml ;;
                    esac
                    return 0
                }""");
        return sh.toString();
    }

    private static void aliases(StringBuilder sh, Map<String, String> aliases, String variable) {
        aliases.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                sh.append("        ").append(e.getKey()).append(") ").append(variable)
                        .append("=").append(e.getValue()).append(" ;;\n"));
    }

    private static void cases(StringBuilder sh, Map<String, List<String>> map, String after) {
        map.forEach((key, words) -> {
            sh.append("        \"").append(key).append("\") ");
            if (words.equals(List.of(CompletionSpec.ADDABLE_EXTENSIONS))) {
                sh.append("_vidocq_addable \"$@\"");
            } else {
                sh.append("echo \"").append(join(words)).append("\"");
            }
            sh.append(after).append(" ;;\n");
        });
    }

    private static String join(List<String> words) {
        return words.stream().collect(Collectors.joining(" "));
    }
}
