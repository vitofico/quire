import { useEffect, useRef, useState } from "react";
import { DiscoveryHttpError, type DiscoveryRequest, type RequestMessage, requestMessage } from "./api";

type Generation = { active: boolean; previous?: string };
const costlyRequests = new WeakMap<DiscoveryRequest, Map<string, Generation>>();
const unclearOutcome = { message: "The server may still be working. Check for a saved result before generating again." };

type Operation = { controller: AbortController; costly: boolean };

export function useAiOperation(request: DiscoveryRequest | undefined, scope: string) {
  const current = useRef<Operation | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [paid, setPaid] = useState(false);
  const [error, setError] = useState<RequestMessage>();
  const [ambiguous, setAmbiguous] = useState(false);

  useEffect(() => {
    setBusy(false);
    setError(undefined);
    setAmbiguous(Boolean(request && costlyRequests.get(request)?.has(scope)));
    return () => {
      const operation = current.current;
      current.current = undefined;
      // Once sent, a paid request may finish on the server even if the tab stops waiting.
      if (operation && !operation.costly) operation.controller.abort();
    };
  }, [request, scope]);

  async function run<T>(
    action: (signal: AbortSignal) => Promise<T>,
    receive: (value: T) => void,
    generation?: { previous?: string },
  ) {
    if (current.current || !request) return;
    const operations = costlyRequests.get(request) ?? new Map<string, Generation>();
    const costly = Boolean(generation);
    if (costly && operations.has(scope)) {
      setAmbiguous(true);
      setError(unclearOutcome);
      return;
    }
    const controller = new AbortController();
    const operation = { controller, costly };
    current.current = operation;
    if (costly) {
      operations.set(scope, { active: true, previous: generation?.previous });
      costlyRequests.set(request, operations);
    }
    setBusy(true);
    setPaid(costly);
    setError(undefined);
    try {
      const value = await action(controller.signal);
      if (costly) operations.delete(scope);
      if (current.current === operation) receive(value);
    } catch (cause) {
      const unknown = costly && !(cause instanceof DiscoveryHttpError);
      if (unknown) operations.set(scope, { active: false, previous: generation?.previous });
      else if (costly) operations.delete(scope);
      if (current.current !== operation || controller.signal.aborted) return;
      setError(unknown ? unclearOutcome : requestMessage(cause));
    } finally {
      if (current.current === operation) {
        current.current = undefined;
        setBusy(false);
        setAmbiguous(operations.has(scope));
      }
    }
  }

  function recover(savedTimestamp?: string) {
    if (!request || !savedTimestamp) return;
    const operations = costlyRequests.get(request);
    const generation = operations?.get(scope);
    if (generation && !generation.active && savedTimestamp !== generation.previous) {
      operations?.delete(scope);
      setAmbiguous(false);
    }
  }

  function cancel() {
    const operation = current.current;
    if (!operation) return;
    if (!operation.costly) {
      operation.controller.abort();
      return;
    }
    current.current = undefined;
    setBusy(false);
    setAmbiguous(true);
    setError(unclearOutcome);
  }

  return { run, busy, error, ambiguous, cancel, paid, recover };
}
