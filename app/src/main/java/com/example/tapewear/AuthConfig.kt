package com.example.tapewear

/**
 * Centralized configuration constants for the authentication pipeline.
 * All thresholds, sizes, and weights live here — no more scattered magic numbers.
 */
object AuthConfig {

    // ---- Scoring ----
    const val DEFAULT_MATCH_THRESHOLD = 0.80f
    const val DEFAULT_YOLO_CONF_THRESHOLD = 0.50f
    const val DEFAULT_USE_ML_EMBEDDER = false
    const val DEFAULT_REG_BURST_MS = 10000L
    const val DEFAULT_REG_TARGET_FRAMES = 10
    const val DEFAULT_HANDS_FREE_ENABLED = false
    const val DEFAULT_HANDS_FREE_CONSECUTIVE_HITS = 3

    /** Minimum cosine similarity for a match verdict. */
    var MATCH_THRESHOLD = DEFAULT_MATCH_THRESHOLD
    /** Maximum spread across scored frames to be considered stable. */
    var MAX_SPREAD = 0.20f
    /** Minimum number of successfully scored frames to issue a verdict. */
    var MIN_SCORED_FRAMES = 1

    // ---- YOLO Detection ----
    /** Minimum objectness confidence for YOLO detections. */
    var YOLO_CONF_THRESHOLD = DEFAULT_YOLO_CONF_THRESHOLD
    /** Minimum normalized box area (w*h in [0..1]) accepted from YOLO output. */
    var YOLO_MIN_BOX_AREA_RATIO = 0.01f
    /** IoU threshold used by YOLO post-NMS filtering. */
    var YOLO_NMS_IOU_THRESHOLD = 0.50f
    /** Max detections kept after NMS for rendering/scoring. */
    var YOLO_MAX_DETECTIONS = 1
    /** Shared toggle for demo-video mode in both registration and authentication. */
    var DEMO_MODE = false

    // ---- Hands-Free Mode ----
    /** When enabled, auto-triggers registration/authentication after consecutive YOLO detections. */
    var HANDS_FREE_ENABLED = DEFAULT_HANDS_FREE_ENABLED
    /** Number of consecutive YOLO detections inside the guide box required to auto-trigger. */
    var HANDS_FREE_CONSECUTIVE_HITS = DEFAULT_HANDS_FREE_CONSECUTIVE_HITS

    // ---- Embedder Architecture ----
    /** A/B Toggle to switch from Classical CV math to Siamese Neural Networks */
    var USE_ML_EMBEDDER = DEFAULT_USE_ML_EMBEDDER

    // ---- Embedder (HybridPatchEmbedder) ----
    /** Size the ROI crop is resized to before feature extraction. */
    const val EMBED_CROP_SIZE = 64
    /** Downsampled pixel block size (32×32 = 1024-D). */
    const val EMBED_PATCH_SIZE = 32
    /** Number of spatial cells for gradient histogram (4×4). */
    const val EMBED_CELLS = 4
    /** Number of orientation bins per gradient cell. */
    const val EMBED_ANGLE_BINS = 8
    /** Number of bins per HSV channel for color histogram. */
    const val EMBED_COLOR_BINS = 16
    /** Center-crop ratio applied inside the YOLO ROI. */
    const val EMBED_CENTER_CROP_RATIO = 0.90f

    /** Block weight for pixel features before L2 normalization. */
    const val EMBED_PIX_WEIGHT = 0.35f
    /** Block weight for gradient features before L2 normalization. */
    const val EMBED_GRAD_WEIGHT = 2.0f
    /** Block weight for color histogram features before L2 normalization. */
    const val EMBED_COLOR_WEIGHT = 3.0f

    // ---- Enrollment ----
    /** Duration of the registration capture burst in milliseconds. */
    var REG_BURST_MS = DEFAULT_REG_BURST_MS
    /** Number of high-quality frames to capture during registration before stopping early. */
    var REG_TARGET_FRAMES = DEFAULT_REG_TARGET_FRAMES
    /** Maximum number of pattern slots. */
    const val MAX_SLOTS = 50
    /** Maximum embeddings used per enrollment. */
    const val MAX_ENROLL_EMBEDS = 32

    // ---- Experiment Mode ----
    /** Master toggle for experiment/study mode across all pages. */
    var EXPERIMENT_MODE = false
    /** Illumination condition: "bright" or "dim". */
    var EXPERIMENT_ILLUMINATION = "bright"
    /** Distance condition: "near" or "far". */
    var EXPERIMENT_DISTANCE = "near"
    /** Fixed number of authentication trials per user in experiment mode. */
    const val EXPERIMENT_AUTH_TRIALS = 3
    /** Whether flashlight is used during dim conditions in experiment mode. */
    var EXPERIMENT_FLASH_ENABLED = false
}
