// Copyright (c) 2026 Eddie Aftandilian. Licensed under the MIT License.
// See LICENSE file in the project root for details.
//
// .NET non-backtracking regex benchmark harness. It consumes the resolved
// engine-neutral workload plan emitted by BenchmarkInputMaterializer.

using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

internal static class ResolvedPlanProgram
{
    private const RegexOptions BaseOptions =
        RegexOptions.NonBacktracking | RegexOptions.CultureInvariant;
    private const string EngineName = "dotnet_nonbacktracking";
    private static readonly JsonSerializerOptions JsonOptions =
        new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private static readonly HashSet<string> SupportedOperations =
    [
        "captureGroups",
        "compile",
        "compileAndFind",
        "compileAndFindRotatingUtf16",
        "find",
        "findAllCount",
        "findAllGroupLengthSum",
        "findAllLengthSum",
        "findGroup",
        "findGroupPresent",
        "findRotatingUtf16",
        "lookingAt",
        "matches",
        "matchesCorpus",
        "matchesGroupLengthSum",
        "replaceAll",
        "replaceAllLengthSum",
        "replaceFirst",
        "splitLengthSum"
    ];

    private static JsonObject inputs = null!;
    private static JsonArray workloads = null!;
    private static Dictionary<string, string> patternProfile = [];
    private static Dictionary<string, string> replacementProfile = [];
    private static string inputDirectory = "";
    private static string manifestPath = "";
    private static volatile bool boolSink;
    private static volatile int intSink;
    private static volatile string? stringSink;
    private static volatile Regex? regexSink;

    private sealed record BenchResult(
        string Engine,
        string Benchmark,
        double Score,
        double Error,
        string Unit);

    private sealed record Exclusion(string Engine, string Benchmark, string Reason);

    private sealed record Options(
        bool Smoke,
        bool List,
        bool ListExclusions,
        string? ColdChild,
        List<string> Filters);

    private sealed record Prepared(
        JsonObject Workload,
        string Id,
        string Operation,
        string Unit,
        string MeasurementMode,
        string[] PatternSources,
        Regex[] Regexes,
        Regex? FullRegex,
        string[] InputTexts,
        Action Action);

    private static int Main(string[] args)
    {
        if (args.SequenceEqual(["--self-test"]))
        {
            return RunSelfTest();
        }
        Options options = ParseOptions(args);
        LoadManifest();
        List<Exclusion> exclusions = [];
        List<Prepared> prepared = [];

        foreach (JsonNode? raw in workloads)
        {
            JsonObject workload = raw?.AsObject()
                ?? throw new InvalidDataException("Null resolved workload");
            string id = String(workload, "id");
            if (options.ColdChild is not null
                ? options.ColdChild != id
                : !MatchesFilter(id, options.Filters))
            {
                continue;
            }

            string? staticReason = StaticExclusionReason(workload);
            if (staticReason is not null)
            {
                exclusions.Add(new(EngineName, id, staticReason));
                continue;
            }

            try
            {
                prepared.Add(Prepare(workload, options.ColdChild == id));
            }
            catch (ArgumentException exception)
            {
                exclusions.Add(
                    new(
                        EngineName,
                        id,
                        "pattern is not accepted by .NET non-backtracking mode: "
                            + FirstLine(exception.Message)));
            }
            catch (NotSupportedException exception)
            {
                exclusions.Add(
                    new(
                        EngineName,
                        id,
                        "pattern is not supported by .NET non-backtracking mode: "
                            + FirstLine(exception.Message)));
            }
        }

        if (options.ColdChild is not null)
        {
            Prepared workload = prepared.SingleOrDefault()
                ?? throw new InvalidDataException(
                    $"Cold-start workload is unavailable: {options.ColdChild}");
            Stopwatch stopwatch = Stopwatch.StartNew();
            workload.Action();
            stopwatch.Stop();
            Console.WriteLine(
                (stopwatch.ElapsedTicks * 1_000_000_000.0 / Stopwatch.Frequency)
                    .ToString("R", CultureInfo.InvariantCulture));
            return 0;
        }

        if (options.List || options.ListExclusions)
        {
            if (options.List)
            {
                foreach (Prepared workload in prepared)
                {
                    Console.WriteLine(workload.Id);
                }
            }
            if (options.ListExclusions)
            {
                foreach (Exclusion exclusion in exclusions)
                {
                    Console.WriteLine(JsonSerializer.Serialize(exclusion, JsonOptions));
                }
            }
            return 0;
        }

        foreach (Prepared workload in prepared)
        {
            Validate(workload);
            BenchResult result = workload.MeasurementMode == "singleShotColdStart"
                ? MeasureCold(workload, options.Smoke)
                : Measure(workload, options.Smoke);
            Console.WriteLine(JsonSerializer.Serialize(result, JsonOptions));
        }
        GC.KeepAlive(stringSink);
        GC.KeepAlive(regexSink);
        return 0;
    }

    private static Options ParseOptions(string[] args)
    {
        bool smoke = false;
        bool list = false;
        bool listExclusions = false;
        string? coldChild = null;
        List<string> filters = [];
        manifestPath = "../../target/benchmark-corpus/manifest.json";
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--manifest" when i + 1 < args.Length:
                    manifestPath = args[++i];
                    break;
                case "--smoke":
                    smoke = true;
                    break;
                case "--list":
                    list = true;
                    break;
                case "--list-exclusions":
                    listExclusions = true;
                    break;
                case "--cold-child" when i + 1 < args.Length:
                    coldChild = args[++i];
                    break;
                default:
                    filters.Add(args[i]);
                    break;
            }
        }
        return new(smoke, list, listExclusions, coldChild, filters);
    }

    private static void LoadManifest()
    {
        JsonObject manifest = JsonNode.Parse(File.ReadAllText(manifestPath))!.AsObject();
        if (manifest["version"]!.GetValue<int>() != 1)
        {
            throw new InvalidDataException("Unsupported benchmark input manifest version");
        }

        inputDirectory = Path.GetDirectoryName(Path.GetFullPath(manifestPath))!;
        inputs = manifest["inputs"]!.AsObject();
        workloads = manifest["resolvedWorkloads"]?.AsArray()
            ?? throw new InvalidDataException(
                "Manifest has no resolvedWorkloads; rerun materialize-benchmark-inputs.sh");
        patternProfile = LoadProfile(
            manifest["benchmarkData"]!,
            "patternProfiles",
            "dotnet");
        replacementProfile = LoadProfile(
            manifest["benchmarkData"]!,
            "replacementProfiles",
            "dotnet");
    }

    private static Dictionary<string, string> LoadProfile(
        JsonNode data,
        string registry,
        string profileId)
    {
        Dictionary<string, string> result = [];
        JsonArray? entries = data[registry]?[profileId]?.AsArray();
        if (entries is null)
        {
            return result;
        }
        foreach (JsonNode? rawEntry in entries)
        {
            JsonNode entry = rawEntry
                ?? throw new InvalidDataException(
                    $"Null {registry} entry: {profileId}");
            result.Add(String(entry, "java"), String(entry, "alternate"));
        }
        return result;
    }

    private static string? StaticExclusionReason(JsonObject workload)
    {
        string operation = String(workload, "operation");
        if (!SupportedOperations.Contains(operation))
        {
            return operation switch
            {
                "analyzePattern" or "cachedAnalysis" or "compileAndAnalyze"
                    or "diagnosticsFind" =>
                    "SafeRE diagnostics have no .NET Regex equivalent",
                "dfaCacheGrowth" => ".NET does not expose its regex-engine cache state",
                "patternSetMatches" => ".NET Regex has no compiled multi-pattern set API",
                "matcherConstruction" or "utf8CaptureBounds" or "utf8DecodeFind"
                    or "utf8Replacement" =>
                    "workload measures SafeRE's byte-oriented UTF-8 API",
                "manualReplaceAll" =>
                    ".NET Regex has no append-replacement and append-tail matcher API",
                "matcherRegionFind" or "matcherResetFind" =>
                    ".NET Regex has no retained mutable matcher lifecycle",
                _ => $"operation is not implemented by the .NET runner: {operation}"
            };
        }

        string mode = String(workload, "measurement.mode");
        if (mode == "retainedMemory")
        {
            return "retained-heap measurement is not available from the .NET Regex API";
        }
        if (mode is not ("averageTime" or "compileOnly" or "singleShotColdStart"))
        {
            return $"measurement mode is not implemented by the .NET runner: {mode}";
        }

        HashSet<string> representations =
            Strings(workload, "inputRepresentations").ToHashSet();
        if (!representations.Contains("javaString"))
        {
            return "workload has no UTF-16 string representation";
        }

        string flagSet = OptionalString(workload["arguments"], "flagSet") ?? "0";
        if (flagSet == "CASE_INSENSITIVE")
        {
            return ".NET cannot combine non-backtracking mode with Java's ASCII-only "
                + "case-insensitive semantics";
        }
        string[] patterns = Strings(workload, "patterns");
        if (patterns.Any(EnablesInlineCaseInsensitive))
        {
            return ".NET inline case-insensitive matching is Unicode-aware, unlike Java's "
                + "default ASCII-only mode";
        }
        bool unicodeCharacterClass = flagSet.Contains(
            "UNICODE_CHARACTER_CLASS",
            StringComparison.Ordinal);
        if (!unicodeCharacterClass && patterns.Any(UsesJavaAsciiShorthand))
        {
            return ".NET gives \\d, \\s, and \\w Unicode semantics while Java defaults "
                + "to ASCII semantics";
        }
        return null;
    }

    private static bool UsesJavaAsciiShorthand(string pattern)
    {
        for (int i = 0; i + 1 < pattern.Length; i++)
        {
            if (pattern[i] != '\\')
            {
                continue;
            }
            if ("dDsSwW".Contains(pattern[i + 1]))
            {
                return true;
            }
            i++;
        }
        return false;
    }

    private static bool EnablesInlineCaseInsensitive(string pattern)
    {
        for (int start = 0; start + 2 < pattern.Length; start++)
        {
            if (pattern[start] != '(' || pattern[start + 1] != '?')
            {
                continue;
            }
            bool enabled = true;
            for (int i = start + 2; i < pattern.Length; i++)
            {
                char current = pattern[i];
                if (current is ':' or ')')
                {
                    break;
                }
                if (current == '-')
                {
                    enabled = false;
                }
                else if (current == 'i' && enabled)
                {
                    return true;
                }
                else if (!char.IsAsciiLetter(current))
                {
                    break;
                }
            }
        }
        return false;
    }

    private static Prepared Prepare(JsonObject workload, bool skipPatternProbe)
    {
        string id = String(workload, "id");
        string operation = String(workload, "operation");
        string mode = String(workload, "measurement.mode");
        string unit = String(workload, "measurement.timingUnit") switch
        {
            "nanoseconds" => "ns/op",
            "microseconds" => "us/op",
            "milliseconds" => "ms/op",
            string other => throw new InvalidDataException(
                $"Unsupported timing unit for {id}: {other}")
        };
        string flagSet = OptionalString(workload["arguments"], "flagSet") ?? "0";
        RegexOptions options = OptionsFor(flagSet);
        string[] patternSources = Strings(workload, "patterns")
            .Select(SelectPattern)
            .ToArray();
        foreach (string pattern in patternSources)
        {
            if (!skipPatternProbe)
            {
                _ = new Regex(pattern, options);
            }
        }
        Regex[] regexes = operation == "compile" ? [] :
            patternSources.Select(pattern => new Regex(pattern, options)).ToArray();
        Regex? fullRegex = operation is "matches" or "matchesCorpus"
            or "matchesGroupLengthSum" or "captureGroups"
            ? new Regex(@"\A(?:" + patternSources[0] + @")\z", options)
            : null;
        string[] inputTexts = Strings(workload, "inputs")
            .Select(LoadBenchmarkInput)
            .ToArray();
        Action action = BuildAction(
            workload,
            operation,
            options,
            patternSources,
            regexes,
            fullRegex,
            inputTexts);
        return new(
            workload,
            id,
            operation,
            unit,
            mode,
            patternSources,
            regexes,
            fullRegex,
            inputTexts,
            action);
    }

    private static Action BuildAction(
        JsonObject workload,
        string operation,
        RegexOptions options,
        string[] patternSources,
        Regex[] regexes,
        Regex? fullRegex,
        string[] inputTexts)
    {
        JsonNode arguments = workload["arguments"]!;
        int[] groups = OptionalInts(arguments, "groups");
        int group = OptionalInt(arguments, "group") ?? 0;
        string replacement = SelectReplacement(
            OptionalString(arguments, "replacement") ?? "");
        int limit = OptionalInt(arguments, "limit") ?? 0;
        int seed = OptionalInt(arguments, "seed") ?? 0;
        int count = OptionalInt(arguments, "count") ?? 0;
        string[] rotatingInputs = operation.Contains(
            "RotatingUtf16",
            StringComparison.Ordinal)
            ? RotatingUtf16Inputs(seed, count)
            : [];
        int rotatingIndex = 0;
        Regex regex = regexes.FirstOrDefault()!;
        string input = inputTexts.FirstOrDefault() ?? "";

        return operation switch
        {
            "matches" => () => boolSink = fullRegex!.IsMatch(input),
            "find" => () => boolSink = regex.IsMatch(input),
            "lookingAt" => () =>
            {
                Match match = regex.Match(input);
                boolSink = match.Success && match.Index == 0;
            },
            "findAllCount" => () => intSink = regex.Matches(input).Count,
            "matchesCorpus" => () =>
                intSink = inputTexts.Count(text => fullRegex!.IsMatch(text)),
            "matchesGroupLengthSum" => () =>
                intSink = inputTexts.Select(text => fullRegex!.Match(text))
                    .Where(match => match.Success)
                    .Sum(match => GroupLengthSum(match, groups)),
            "findAllLengthSum" => () =>
                intSink = regex.Matches(input).Sum(match => match.Length),
            "findAllGroupLengthSum" => () =>
                intSink = regex.Matches(input).Sum(match => GroupLengthSum(match, groups)),
            "captureGroups" => () => stringSink = CaptureGroups(fullRegex!.Match(input), groups),
            "replaceFirst" => () => stringSink = regex.Replace(input, replacement, 1),
            "replaceAll" => () => stringSink = regex.Replace(input, replacement),
            "replaceAllLengthSum" => () =>
                intSink = regexes.Sum(item => item.Replace(input, replacement).Length),
            "splitLengthSum" => () => intSink = SplitLengthSum(regex, input, limit),
            "compile" => () => regexSink = new Regex(patternSources[0], options),
            "compileAndFind" => () =>
                boolSink = new Regex(patternSources[0], options).IsMatch(input),
            "findRotatingUtf16" => () =>
            {
                boolSink = regex.IsMatch(rotatingInputs[rotatingIndex]);
                rotatingIndex = (rotatingIndex + 1) % rotatingInputs.Length;
            },
            "compileAndFindRotatingUtf16" => () =>
            {
                boolSink = new Regex(patternSources[0], options)
                    .IsMatch(rotatingInputs[rotatingIndex]);
                rotatingIndex = (rotatingIndex + 1) % rotatingInputs.Length;
            },
            "findGroupPresent" => () =>
            {
                Match match = regex.Match(input);
                boolSink = match.Success && match.Groups[group].Success;
            },
            "findGroup" => () =>
            {
                Match match = regex.Match(input);
                stringSink = match.Success && match.Groups[group].Success
                    ? match.Groups[group].Value
                    : null;
            },
            _ => throw new InvalidDataException($"Unsupported operation: {operation}")
        };
    }

    private static void Validate(Prepared prepared)
    {
        JsonNode? expected = prepared.Workload["expected"];
        if (expected is null)
        {
            return;
        }

        prepared.Action();
        JsonNode value = expected["value"]!;
        string type = expected["type"]!.GetValue<string>();
        bool matches = type switch
        {
            "boolean" => boolSink == value.GetValue<bool>(),
            "integer" => intSink == value.GetValue<long>(),
            "string" => stringSink == value.GetValue<string>(),
            _ => throw new InvalidDataException(
                $"Unsupported expected result type for {prepared.Id}: {type}")
        };
        if (!matches)
        {
            throw new InvalidDataException(
                $"Result mismatch for {prepared.Id}: expected {value}, got "
                    + ActualResult(type));
        }
    }

    private static object? ActualResult(string type) =>
        type switch
        {
            "boolean" => boolSink,
            "integer" => intSink,
            "string" => stringSink,
            _ => null
        };

    private static BenchResult Measure(Prepared workload, bool smoke)
    {
        if (smoke)
        {
            Stopwatch stopwatch = Stopwatch.StartNew();
            workload.Action();
            stopwatch.Stop();
            double nanoseconds =
                stopwatch.ElapsedTicks * 1_000_000_000.0 / Stopwatch.Frequency;
            return Result(workload, nanoseconds, 0);
        }

        for (int iteration = 0; iteration < 2; iteration++)
        {
            RunUntil(workload.Action, TimeSpan.FromSeconds(2));
        }
        double[] samples = new double[10];
        for (int iteration = 0; iteration < samples.Length; iteration++)
        {
            (long operations, double nanoseconds) =
                RunUntil(workload.Action, TimeSpan.FromSeconds(2));
            samples[iteration] = nanoseconds / operations;
        }
        double mean = samples.Average();
        double variance = samples.Sum(sample => Math.Pow(sample - mean, 2))
            / (samples.Length - 1);
        double error = 4.781 * Math.Sqrt(variance) / Math.Sqrt(samples.Length);
        return Result(workload, mean, error);
    }

    private static (long Operations, double Nanoseconds) RunUntil(
        Action action,
        TimeSpan duration)
    {
        long operations = 0;
        long start = Stopwatch.GetTimestamp();
        long deadline = start + (long)(duration.TotalSeconds * Stopwatch.Frequency);
        do
        {
            action();
            operations++;
        }
        while (Stopwatch.GetTimestamp() < deadline);
        double nanoseconds =
            (Stopwatch.GetTimestamp() - start) * 1_000_000_000.0 / Stopwatch.Frequency;
        return (operations, nanoseconds);
    }

    private static BenchResult MeasureCold(Prepared workload, bool smoke)
    {
        int samples = smoke ? 1 : 5;
        double[] values = new double[samples];
        for (int i = 0; i < samples; i++)
        {
            ProcessStartInfo startInfo = new()
            {
                FileName = Environment.ProcessPath
                    ?? throw new InvalidOperationException("Cannot locate current executable"),
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false
            };
            startInfo.ArgumentList.Add("--manifest");
            startInfo.ArgumentList.Add(Path.GetFullPath(manifestPath));
            startInfo.ArgumentList.Add("--cold-child");
            startInfo.ArgumentList.Add(workload.Id);
            using Process process = Process.Start(startInfo)
                ?? throw new InvalidOperationException("Could not start cold benchmark process");
            string stdout = process.StandardOutput.ReadToEnd();
            string stderr = process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode != 0
                || !double.TryParse(
                    stdout.Trim(),
                    NumberStyles.Float,
                    CultureInfo.InvariantCulture,
                    out values[i]))
            {
                throw new InvalidOperationException(
                    $"Cold benchmark child failed for {workload.Id}: {stderr}{stdout}");
            }
        }
        double mean = values.Average();
        double error = samples == 1
            ? 0
            : 2.776
                * Math.Sqrt(values.Sum(value => Math.Pow(value - mean, 2)) / (samples - 1))
                / Math.Sqrt(samples);
        return Result(workload, mean, error);
    }

    private static BenchResult Result(
        Prepared workload,
        double nanoseconds,
        double errorNanoseconds)
    {
        double divisor = workload.Unit switch
        {
            "us/op" => 1_000.0,
            "ms/op" => 1_000_000.0,
            _ => 1.0
        };
        return new(
            EngineName,
            workload.Id,
            Math.Round(nanoseconds / divisor, 3),
            Math.Round(errorNanoseconds / divisor, 3),
            workload.Unit);
    }

    private static RegexOptions OptionsFor(string flagSet) =>
        flagSet switch
        {
            "0" or "UNICODE_CHARACTER_CLASS" => BaseOptions,
            "CASE_INSENSITIVE" or "CASE_INSENSITIVE_UNICODE_CASE"
                or "CASE_INSENSITIVE_UNICODE_CHARACTER_CLASS" =>
                BaseOptions | RegexOptions.IgnoreCase,
            _ => throw new NotSupportedException($"Java flag set has no mapping: {flagSet}")
        };

    private static string SelectPattern(string javaPattern) =>
        patternProfile.GetValueOrDefault(javaPattern, javaPattern);

    private static string SelectReplacement(string javaReplacement) =>
        replacementProfile.GetValueOrDefault(javaReplacement, javaReplacement);

    private static string LoadBenchmarkInput(string key)
    {
        JsonObject entry = inputs[key]?.AsObject()
            ?? throw new InvalidDataException($"Unknown materialized benchmark input: {key}");
        string path = Path.Combine(inputDirectory, String(entry, "file"));
        byte[] bytes = File.ReadAllBytes(path);
        int expectedSize = Int(entry, "utf8Bytes");
        if (bytes.Length != expectedSize)
        {
            throw new InvalidDataException(
                $"Materialized benchmark input {key} has size {bytes.Length}, "
                    + $"expected {expectedSize}");
        }
        string actualHash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        string expectedHash = String(entry, "sha256");
        if (actualHash != expectedHash)
        {
            throw new InvalidDataException(
                $"Materialized benchmark input {key} has SHA-256 {actualHash}, "
                    + $"expected {expectedHash}");
        }
        return Encoding.UTF8.GetString(bytes);
    }

    private static int GroupLengthSum(Match match, IEnumerable<int> groups) =>
        groups.Sum(group => match.Groups[group].Success ? match.Groups[group].Length : 0);

    private static string CaptureGroups(Match match, IEnumerable<int> groups) =>
        match.Success
            ? string.Concat(
                groups.Select(group =>
                    match.Groups[group].Success ? match.Groups[group].Value : ""))
            : "";

    private static int SplitLengthSum(Regex regex, string input, int limit)
    {
        string[] parts = limit > 0 ? regex.Split(input, limit) : regex.Split(input);
        int length = parts.Length;
        if (limit == 0)
        {
            while (length > 0 && parts[length - 1].Length == 0)
            {
                length--;
            }
        }
        return length + parts.Take(length).Sum(part => part.Length);
    }

    private static string[] RotatingUtf16Inputs(int seed, int count)
    {
        JavaRandom random = new(seed);
        string[] result = new string[count];
        for (int i = 0; i < count; i++)
        {
            result[i] = new string(unchecked((char)random.NextInt()), 1);
        }
        return result;
    }

    private static int RunSelfTest()
    {
        int[] expected = [0xF1D9, 0x75DF, 0x36DF, 0xCFC7, 0x4907, 0xF2F7, 0x6405, 0xB194];
        string[] actual = RotatingUtf16Inputs(95_413_077, expected.Length);
        if (!actual.Select(value => (int)value[0]).SequenceEqual(expected))
        {
            throw new InvalidOperationException(
                "Java Random compatibility self-test produced the wrong UTF-16 sequence");
        }
        if (!UsesJavaAsciiShorthand(@"\d+")
            || UsesJavaAsciiShorthand(@"\\d+")
            || !EnablesInlineCaseInsensitive("(?is)abc")
            || !EnablesInlineCaseInsensitive("(?mi:abc)")
            || EnablesInlineCaseInsensitive("(?-i:abc)"))
        {
            throw new InvalidOperationException(
                "Java regex compatibility classification self-test failed");
        }
        Regex letterWithoutUppercase =
            new(@"[\p{L}-[\p{Lu}]]+", BaseOptions);
        if (!letterWithoutUppercase.IsMatch("a") || letterWithoutUppercase.IsMatch("A"))
        {
            throw new InvalidOperationException(
                ".NET character-class subtraction self-test failed");
        }
        replacementProfile = new() { ["$1word"] = "${1}word" };
        if (SelectReplacement("$1word") != "${1}word"
            || SelectReplacement("$1") != "$1")
        {
            throw new InvalidOperationException(
                ".NET replacement-profile selection self-test failed");
        }
        return 0;
    }

    private sealed class JavaRandom(int seed)
    {
        private const long Multiplier = 0x5DEECE66DL;
        private const long Addend = 0xBL;
        private const long Mask = (1L << 48) - 1;
        private long state = (seed ^ Multiplier) & Mask;

        public int Next(int bound)
        {
            if ((bound & -bound) == bound)
            {
                return (int)((bound * (long)NextBits(31)) >> 31);
            }
            int bits;
            int value;
            do
            {
                bits = NextBits(31);
                value = bits % bound;
            }
            while (bits - value + (bound - 1) < 0);
            return value;
        }

        public int NextInt() => NextBits(32);

        private int NextBits(int bits)
        {
            state = (state * Multiplier + Addend) & Mask;
            return (int)((ulong)state >> (48 - bits));
        }
    }

    private static bool MatchesFilter(string name, IReadOnlyCollection<string> filters) =>
        filters.Count == 0 || filters.Any(name.Contains);

    private static string FirstLine(string value) =>
        value.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries).FirstOrDefault()
            ?? value;

    private static JsonNode Get(JsonNode node, string path)
    {
        JsonNode? current = node;
        foreach (string part in path.Split('.'))
        {
            current = current?[part];
        }
        return current ?? throw new InvalidDataException($"Missing benchmark data: {path}");
    }

    private static string String(JsonNode node, string path) =>
        Get(node, path).GetValue<string>();

    private static int Int(JsonNode node, string path) => Get(node, path).GetValue<int>();

    private static string[] Strings(JsonNode node, string path) =>
        Get(node, path).AsArray().Select(value => value!.GetValue<string>()).ToArray();

    private static string? OptionalString(JsonNode? node, string property) =>
        node?[property]?.GetValue<string>();

    private static int? OptionalInt(JsonNode? node, string property) =>
        node?[property]?.GetValue<int>();

    private static int[] OptionalInts(JsonNode? node, string property) =>
        node?[property]?.AsArray().Select(value => value!.GetValue<int>()).ToArray() ?? [];
}
