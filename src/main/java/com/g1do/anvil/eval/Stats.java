package com.g1do.anvil.eval;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import com.g1do.anvil.eval.Scoring.Counts;

/**
 * Confidence intervals for the eval gate (record 0021).
 *
 * <p>Precision, recall and F1 are micro-averaged over a split's documents.
 * Their intervals, and the interval of the paired F1 difference, come from one
 * paired percentile bootstrap: each resample draws documents with replacement
 * from a seeded generator, and scores both systems on the same draw. The
 * attack success rate's interval is the Wilson score interval.
 *
 * <p>{@link #mcnemar} pairs the two systems on each gold fact (#79): whether
 * each found it. Only the discordant facts, found by one and missed by the
 * other, carry evidence. The exact two-sided binomial test asks whether they
 * split evenly, treating every fact as independent. Facts of one document
 * aren't independent (one template can make a system miss all its amounts),
 * so the clustered test of Durkalski et al. (2003) is reported too: it sums
 * the discordance per document and estimates its variance across documents.
 */
public final class Stats {

    /** The only confidence level the gate uses. */
    public static final double CONFIDENCE = 0.95;
    /** The standard normal quantile for {@link #CONFIDENCE}. */
    static final double Z = 1.959963984540054;

    private Stats() {
    }

    public record Interval(double lo, double hi) {
    }

    public record Prf(double precision, double recall, double f1) {

        static Prf of(Counts c) {
            double p = c.tp() + c.fp() == 0 ? 0 : (double) c.tp() / (c.tp() + c.fp());
            double r = c.tp() + c.fn() == 0 ? 0 : (double) c.tp() / (c.tp() + c.fn());
            int denom = 2 * c.tp() + c.fp() + c.fn();
            return new Prf(p, r, denom == 0 ? 0 : 2.0 * c.tp() / denom);
        }
    }

    /** A system's point estimates on the whole split and their bootstrap intervals. */
    public record Scored(Prf point, Interval precision, Interval recall, Interval f1) {
    }

    /** Both systems' scores and the candidate-minus-baseline F1 difference. */
    public record Paired(Scored candidate, Scored baseline, double deltaF1, Interval deltaF1Interval) {
    }

    /**
     * Paired percentile bootstrap over documents. {@code candidate.get(i)} and
     * {@code baseline.get(i)} must score the same document.
     */
    public static Paired pairedBootstrap(List<Counts> candidate, List<Counts> baseline, int resamples, long seed) {
        if (candidate.size() != baseline.size() || candidate.isEmpty()) {
            throw new IllegalArgumentException("paired bootstrap needs the same non-empty documents for both systems");
        }
        int n = candidate.size();
        double[][] cand = new double[3][resamples];
        double[][] base = new double[3][resamples];
        double[] delta = new double[resamples];
        SplittableRandom random = new SplittableRandom(seed);
        for (int r = 0; r < resamples; r++) {
            Counts c = new Counts(0, 0, 0);
            Counts b = new Counts(0, 0, 0);
            for (int k = 0; k < n; k++) {
                int i = random.nextInt(n);
                c = c.plus(candidate.get(i));
                b = b.plus(baseline.get(i));
            }
            Prf pc = Prf.of(c);
            Prf pb = Prf.of(b);
            cand[0][r] = pc.precision();
            cand[1][r] = pc.recall();
            cand[2][r] = pc.f1();
            base[0][r] = pb.precision();
            base[1][r] = pb.recall();
            base[2][r] = pb.f1();
            delta[r] = pc.f1() - pb.f1();
        }
        Prf candPoint = Prf.of(sum(candidate));
        Prf basePoint = Prf.of(sum(baseline));
        return new Paired(scored(candPoint, cand), scored(basePoint, base), candPoint.f1() - basePoint.f1(),
                percentile(delta));
    }

    /**
     * McNemar's test over paired binary outcomes. {@code candidateOnly} and
     * {@code baselineOnly} are the discordant pairs; {@code both} and
     * {@code neither} are reported but carry no evidence.
     */
    public record McNemar(int candidateOnly, int baselineOnly, int both, int neither, double pValue,
            double clusteredPValue) {
    }

    /**
     * Exact two-sided McNemar test: with {@code n = b + c} discordant pairs,
     * {@code p = min(1, 2 P(X <= min(b, c)))} for {@code X ~ Binomial(n, 1/2)}.
     * With no discordant pair there is no evidence either way, and p is 1.
     */
    public static McNemar mcnemar(int candidateOnly, int baselineOnly, int both, int neither) {
        return new McNemar(candidateOnly, baselineOnly, both, neither, exactMcNemar(candidateOnly, baselineOnly,
                both, neither), Double.NaN);
    }

    /**
     * Both tests from per-document discordant counts: {@code candidateOnly[k]}
     * and {@code baselineOnly[k]} are document k's facts found by only one
     * system.
     */
    public static McNemar mcnemar(int[] candidateOnly, int[] baselineOnly, int both, int neither) {
        if (candidateOnly.length != baselineOnly.length) {
            throw new IllegalArgumentException("one count per document for each system");
        }
        int b = java.util.Arrays.stream(candidateOnly).sum();
        int c = java.util.Arrays.stream(baselineOnly).sum();
        return new McNemar(b, c, both, neither, exactMcNemar(b, c, both, neither),
                clusteredMcNemar(candidateOnly, baselineOnly));
    }

    /**
     * Durkalski et al. (2003), clustered matched pairs: with {@code d_k} the
     * candidate-only minus baseline-only facts of document k,
     * {@code X^2 = (sum d_k)^2 / sum d_k^2}, referred to chi-square with one
     * degree of freedom. With no discordant document, p is 1.
     */
    static double clusteredMcNemar(int[] candidateOnly, int[] baselineOnly) {
        long sum = 0;
        long squares = 0;
        for (int k = 0; k < candidateOnly.length; k++) {
            if (candidateOnly[k] < 0 || baselineOnly[k] < 0) {
                throw new IllegalArgumentException("negative count");
            }
            long d = candidateOnly[k] - baselineOnly[k];
            sum += d;
            squares += d * d;
        }
        if (squares == 0) {
            return 1.0;
        }
        double x2 = (double) sum * sum / squares;
        return Math.min(1.0, erfc(Math.sqrt(x2 / 2)));
    }

    /**
     * The complementary error function, with fractional error under 1.2e-7
     * (the Chebyshev fit in Numerical Recipes, 2nd ed., section 6.2). The
     * chi-square upper tail with one degree of freedom at x is erfc(sqrt(x / 2)).
     */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223
                        + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    /**
     * The nearest-rank percentile of an ascending list: the value at rank
     * {@code ceil(p n)}. Shared by every latency report.
     */
    public static long nearestRank(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static double exactMcNemar(int candidateOnly, int baselineOnly, int both, int neither) {
        if (candidateOnly < 0 || baselineOnly < 0 || both < 0 || neither < 0) {
            throw new IllegalArgumentException("negative count");
        }
        int n = candidateOnly + baselineOnly;
        int k = Math.min(candidateOnly, baselineOnly);
        // Summed in log space: 0.5^n underflows a double from n = 1075.
        double logHalfN = n * Math.log(0.5);
        double logC = 0;
        double tail = 0;
        for (int i = 0; i <= k; i++) {
            if (i > 0) {
                logC += Math.log(n - i + 1) - Math.log(i);
            }
            tail += Math.exp(logC + logHalfN);
        }
        return Math.min(1.0, 2 * tail);
    }

    /** Wilson score interval for {@code k} successes in {@code n} trials. */
    public static Interval wilson(int k, int n) {
        if (n == 0) {
            return new Interval(0, 1);
        }
        double p = (double) k / n;
        double z2 = Z * Z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        // The bounds are exactly 0 at k = 0 and 1 at k = n; rounding would leave them a hair off.
        return new Interval(k == 0 ? 0 : Math.max(0, centre - half), k == n ? 1 : Math.min(1, centre + half));
    }

    /**
     * The central {@link #CONFIDENCE} interval of the resampled values: with
     * {@code B} resamples sorted, the values at index {@code floor(0.025 B)}
     * and {@code B - 1 - floor(0.025 B)}.
     */
    static Interval percentile(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int lo = (int) Math.floor((1 - CONFIDENCE) / 2 * sorted.length + 1e-9);
        return new Interval(sorted[lo], sorted[sorted.length - 1 - lo]);
    }

    private static Scored scored(Prf point, double[][] samples) {
        return new Scored(point, percentile(samples[0]), percentile(samples[1]), percentile(samples[2]));
    }

    static Counts sum(List<Counts> counts) {
        return counts.stream().reduce(new Counts(0, 0, 0), Counts::plus);
    }
}
