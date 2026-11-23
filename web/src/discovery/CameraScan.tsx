import { type Translate } from "./translation";
import { useEffect, useRef, useState } from "react";
import { normalizeIsbn } from "./books";

type BarcodeDetectorType = {
  new(options: { formats: string[] }): {
    detect(video: HTMLVideoElement): Promise<{ rawValue: string }[]>;
  };
  getSupportedFormats?(): Promise<string[]>;
};

function detectorConstructor(): BarcodeDetectorType | undefined {
  return (window as Window & { BarcodeDetector?: BarcodeDetectorType }).BarcodeDetector;
}

export function supportsCameraScanning(): boolean {
  return Boolean(navigator.mediaDevices && detectorConstructor());
}

export function CameraScan({ onScan, onClose, t }: { t: Translate; onScan: (isbn: string) => void; onClose: () => void }) {
  const [error, setError] = useState("");
  const preview = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    let active = true;
    let stream: MediaStream | undefined;
    let timer: number | undefined;

    async function scan() {
      try {
        const Detector = detectorConstructor();
        const formats = await Detector?.getSupportedFormats?.();
        if (!active) return;
        if (!Detector || (formats && !formats.includes("ean_13"))) {
          setError("This browser cannot scan ISBN barcodes. Type the ISBN below.");
          return;
        }
        const detector = new Detector({ formats: ["ean_13"] });
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: "environment" } } });
        if (!active || !preview.current) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        preview.current.srcObject = stream;
        await preview.current.play();

        async function detect() {
          if (!active || !preview.current) return;
          try {
            const codes = await detector.detect(preview.current);
            if (!active) return;
            const isbn = codes.map((code) => normalizeIsbn(code.rawValue)).find(Boolean);
            if (isbn) {
              onScan(isbn);
              return;
            }
            timer = window.setTimeout(detect, 180);
          } catch {
            if (active) setError("Scanning stopped. Type the ISBN below.");
            stream?.getTracks().forEach((track) => track.stop());
          }
        }

        await detect();
      } catch {
        stream?.getTracks().forEach((track) => track.stop());
        if (active) setError("Camera access is unavailable. Type the ISBN below.");
      }
    }

    void scan();
    return () => {
      active = false;
      window.clearTimeout(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [onScan]);

  return (
    <div>
      {error ? <p role="alert">{t(error)}</p> : <video aria-label={t("ISBN barcode camera preview")} ref={preview} playsInline muted />}
      <button type="button" onClick={onClose}>{t("Close camera")}</button>
    </div>
  );
}
