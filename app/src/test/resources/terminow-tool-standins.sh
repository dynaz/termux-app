#!/bin/bash
# terminow-tool-standins.sh <dir> <uid> <sudo:none|password|ok> <installed packages> <curl:ok|net>
# Stand-ins for id, dpkg-query, apt-get, sudo and the installer of Claude Code in <dir>/bin, for
# the tests of TerminowToolInstaller. What they were asked to do is written to <dir>/calls.
S=$1; uid=$2; sudo=$3; installed=$4; curlmode=$5
rm -rf "$S"; mkdir -p "$S/bin" "$S/home" "$S/real"
for t in sh bash grep head env cat mkdir chmod uname dirname tr; do p=$(command -v $t) && ln -s "$p" "$S/bin/$t"; done
ln -s /bin/true "$S/bin/true"; echo "$installed" | tr ' ' '\n' > "$S/installed"
cat > "$S/bin/id" <<X
#!/bin/sh
case "\$1" in -u) echo $uid;; -un) [ $uid = 0 ] && echo root || echo dev;; esac
X
cat > "$S/bin/dpkg-query" <<X
#!/bin/sh
p=\$(eval echo \\\${\$#}); grep -qx "\$p" "$S/installed" && printf 'install ok installed' && exit 0; exit 1
X
cat > "$S/bin/apt-get" <<X
#!/bin/sh
echo "apt-get \$*" >> "$S/calls"
case "\$*" in *install*) for a in "\$@"; do case "\$a" in -*|install|DPkg*) ;; *) echo "\$a" >> "$S/installed"
 case "\$a" in git) printf '#!/bin/sh\necho git version 2.43.0\n' > "$S/bin/git";; tmux) printf '#!/bin/sh\necho tmux 3.4\n' > "$S/bin/tmux";;
  curl) printf '#!/bin/sh\nexec "$S/real/curl" "\$@"\n' > "$S/bin/curl";; esac; chmod +x "$S/bin/"* 2>/dev/null;; esac; done;; esac
exit 0
X
if [ "$sudo" != none ]; then cat > "$S/bin/sudo" <<X
#!/bin/sh
echo "sudo \$*" >> "$S/calls"
[ "$sudo" = password ] && exit 1
shift; exec "\$@"
X
fi
# curl: the official installer puts claude in ~/.local/bin, or the network fails
cat > "$S/real/curl" <<X
#!/bin/sh
echo "curl \$*" >> "$S/calls"
[ "$curlmode" = net ] && { echo "curl: (6) Could not resolve host: claude.ai" >&2; exit 6; }
echo 'mkdir -p "\$HOME/.local/bin"; printf "#!/bin/sh\necho 2.1.30 \\(Claude Code\\)\n" > "\$HOME/.local/bin/claude"; chmod +x "\$HOME/.local/bin/claude"; echo installed'
X
chmod +x "$S/bin/"* "$S/real/curl"
case " $installed " in *" curl "*) printf '#!/bin/sh\nexec "%s/real/curl" "$@"\n' "$S" > "$S/bin/curl"; chmod +x "$S/bin/curl";; esac
case " $installed " in *" claude "*) mkdir -p "$S/home/.local/bin"; printf '#!/bin/sh\necho "2.1.29 (Claude Code)"\n' > "$S/home/.local/bin/claude"; chmod +x "$S/home/.local/bin/claude";; esac
