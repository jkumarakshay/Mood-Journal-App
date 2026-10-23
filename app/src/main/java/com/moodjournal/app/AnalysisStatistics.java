package com.moodjournal.app;

/** Welch's two-sided t probability; report sections apply Bonferroni correction separately. */
final class AnalysisStatistics {
    static double probability(double meanA, double varianceA, int nA, double meanB, double varianceB, int nB) {
        double a = varianceA / nA, b = varianceB / nB, error = a + b;
        if (error == 0) return meanA == meanB ? 1 : 0;
        double t2 = (meanA - meanB) * (meanA - meanB) / error;
        double df = error * error / (a * a / (nA - 1) + b * b / (nB - 1));
        double x = df / (df + t2);
        return Math.max(0, Math.min(1, beta(x, df / 2, .5)));
    }
    private static double beta(double x, double a, double b) {
        if (x <= 0) return 0; if (x >= 1) return 1;
        double factor = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x));
        return x < (a + 1) / (a + b + 2) ? factor * fraction(x, a, b) / a : 1 - factor * fraction(1 - x, b, a) / b;
    }
    private static double fraction(double x, double a, double b) {
        double qab = a + b, qap = a + 1, qam = a - 1, c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < 1e-30) d = 1e-30; d = 1 / d; double h = d;
        for (int m = 1; m <= 200; m++) {
            int twice = 2 * m;
            double aa = m * (b - m) * x / ((qam + twice) * (a + twice));
            d = 1 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30; d = 1 / d; h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + twice) * (qap + twice));
            d = 1 + aa * d; if (Math.abs(d) < 1e-30) d = 1e-30;
            c = 1 + aa / c; if (Math.abs(c) < 1e-30) c = 1e-30; d = 1 / d;
            double delta = d * c; h *= delta; if (Math.abs(delta - 1) < 3e-12) break;
        }
        return h;
    }
    private static double logGamma(double value) {
        double[] coefficients = {676.5203681218851, -1259.1392167224028, 771.3234287776531, -176.6150291621406,
                12.507343278686905, -.13857109526572012, 9.984369578019572e-6, 1.5056327351493116e-7};
        if (value < .5) return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * value)) - logGamma(1 - value);
        double z = value - 1, sum = .9999999999998099;
        for (int i = 0; i < coefficients.length; i++) sum += coefficients[i] / (z + i + 1);
        double t = z + 7.5;
        return .9189385332046727 + (z + .5) * Math.log(t) - t + Math.log(sum);
    }
}
