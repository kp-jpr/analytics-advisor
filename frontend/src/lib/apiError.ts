/** Reads an error response, preferring the JSON `message` field when the API sent one. */
export async function readApiErrorMessage(res: Response): Promise<string> {
  const text = (await res.text()).trim();
  if (text.startsWith("{")) {
    try {
      const body = JSON.parse(text) as { message?: unknown };
      if (typeof body.message === "string" && body.message.length > 0) {
        return body.message;
      }
    } catch {
      // The body is not JSON; show the raw text.
    }
  }
  return text;
}
