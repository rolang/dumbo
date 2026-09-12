{
  description = "dumbo - a Flyway compatible database migration tool for Postgres";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };

        version = "0.10.2";

        # Release assets from https://github.com/rolang/dumbo/releases
        # Update `version` and these hashes together when bumping.
        # Get a hash with: nix-prefetch-url --type sha256 <asset-url>
        assets = {
          x86_64-linux = {
            asset = "dumbo-cli-x86_64-linux";
            sha256 = "0pkligx760csm9mmbdl2mq3abhn1ib8jl6rilgh6x7hxa6j07m18";
          };
          aarch64-linux = {
            asset = "dumbo-cli-aarch64-linux";
            sha256 = "0rgsg8a52h3q57d460kb6bzv1wm4v5rcb7y0qzxw1lh57qp96q5h";
          };
          aarch64-darwin = {
            asset = "dumbo-cli-aarch64-macosx";
            sha256 = "1f2ns0glccw0zb2w21fwsl81xmqa4a7qdqv0adwypbpbxvkcx1r2";
          };
        };

        asset = assets.${system} or (throw "dumbo: no release binary for system '${system}'");

        dumbo = pkgs.stdenv.mkDerivation {
          pname = "dumbo";
          inherit version;

          src = pkgs.fetchurl {
            url = "https://github.com/rolang/dumbo/releases/download/v${version}/${asset.asset}";
            sha256 = asset.sha256;
          };

          dontUnpack = true;
          dontBuild = true;
          dontStrip = true; # scala-native binaries aren't stdenv-built ELF/Mach-O, avoid re-stripping surprises

          nativeBuildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [ pkgs.autoPatchelfHook ];

          buildInputs = pkgs.lib.optionals pkgs.stdenv.hostPlatform.isLinux [
            pkgs.s2n-tls
            pkgs.utf8proc
            pkgs.openssl
            pkgs.zlib
            pkgs.stdenv.cc.cc.lib
          ];

          installPhase = ''
            runHook preInstall
            install -Dm755 "$src" "$out/bin/dumbo"
            runHook postInstall
          '';

          # The macOS release binary is dynamically linked against Homebrew's
          # absolute install paths (/opt/homebrew/opt/...). Repoint those at
          # the equivalent nixpkgs outputs. NOTE: untested on real Darwin —
          # verify the exact dylib basenames/versions match before relying on this.
          postFixup = pkgs.lib.optionalString pkgs.stdenv.hostPlatform.isDarwin ''
            install_name_tool -change \
              /opt/homebrew/opt/s2n/lib/libs2n.1.dylib \
              ${pkgs.s2n-tls}/lib/libs2n.1.dylib \
              "$out/bin/dumbo"
            install_name_tool -change \
              /opt/homebrew/opt/utf8proc/lib/libutf8proc.3.dylib \
              ${pkgs.utf8proc}/lib/libutf8proc.3.dylib \
              "$out/bin/dumbo"
            install_name_tool -change \
              /opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib \
              ${pkgs.openssl}/lib/libcrypto.3.dylib \
              "$out/bin/dumbo"
          '';

          meta = {
            description = "Flyway compatible database migration tool for Postgres";
            homepage = "https://github.com/rolang/dumbo";
            license = pkgs.lib.licenses.mit;
            platforms = builtins.attrNames assets;
            mainProgram = "dumbo";
            sourceProvenance = [ pkgs.lib.sourceTypes.binaryNativeCode ];
          };
        };
      in
      {
        packages.default = dumbo;
        packages.dumbo = dumbo;

        apps.default = flake-utils.lib.mkApp { drv = dumbo; };
      }
    );
}
