package dev.heimdall.control;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public interface GitSource {
  record Revision(String commit, Path root) {}

  Revision head() throws Exception;

  /** Development only: hash every configuration input, including scripts and ownership. */
  final class LocalTree implements GitSource {
    private final Path root;

    public LocalTree(Path root) {
      this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Revision head() throws Exception {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var paths = Files.walk(root.resolve("config"))) {
        for (var path : paths.sorted().toList()) {
          if (Files.isSymbolicLink(path))
            throw new IllegalArgumentException("Configuration symlinks are forbidden");
          if (!Files.isRegularFile(path)) continue;
          digest.update(
              root.relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(Files.readAllBytes(path));
          digest.update((byte) 0);
        }
      }
      digest.update(Files.readAllBytes(root.resolve(".github/CODEOWNERS")));
      return new Revision("local-%s".formatted(HexFormat.of().formatHex(digest.digest())), root);
    }
  }

  final class ProtectedBranch implements GitSource {
    private final Path checkout;
    private final String repository, branch;

    public ProtectedBranch(Path checkout, String repository, String branch) {
      this.checkout = checkout;
      this.repository = repository;
      this.branch = branch;
      if (repository == null
          || branch == null
          || !branch.matches("[a-zA-Z0-9_/-]+")
          || branch.contains(".."))
        throw new IllegalArgumentException("Configured repository and protected branch required");
    }

    @Override
    public Revision head() throws Exception {
      if (!Files.exists(checkout.resolve(".git"))) {
        Files.createDirectories(checkout.getParent());
        git(null, "clone", "--no-checkout", "--", repository, checkout.toString());
      }
      git(checkout, "fetch", "--no-tags", "origin", "refs/heads/%s".formatted(branch));
      String commit = git(checkout, "rev-parse", "FETCH_HEAD").strip();
      if (!commit.matches("[0-9a-f]{40,64}"))
        throw new IllegalStateException("Invalid branch head");
      git(checkout, "checkout", "--force", commit, "--", "config", ".github/CODEOWNERS");
      // A clean per-revision directory excludes removed files from the previous checkout.
      Path tree = Files.createTempDirectory(checkout.getParent(), "revision-");
      try {
        git(
            checkout,
            "--work-tree=%s".formatted(tree),
            "checkout",
            commit,
            "--",
            "config",
            ".github/CODEOWNERS");
        return new Revision(commit, tree);
      } catch (Exception e) {
        deleteTree(tree);
        throw e;
      }
    }

    public static void deleteTree(Path path) throws Exception {
      try (var paths = Files.walk(path)) {
        for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
      }
    }
  }

  static String git(Path directory, String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
    if (directory != null) builder.directory(directory.toFile());
    var process = builder.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Git operation timed out");
    }
    String result =
        new String(
            process.getInputStream().readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8);
    if (process.exitValue() != 0)
      throw new IllegalStateException("Approved Git source unavailable");
    return result;
  }
}
