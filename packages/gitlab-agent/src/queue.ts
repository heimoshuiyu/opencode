import type { MRTask } from "./webhook"
import { processMR } from "./processor"

type QueueEntry = {
  task: MRTask
  resolve: () => void
  reject: (error: Error) => void
}

export class TaskQueue {
  private queue: QueueEntry[] = []
  private processing = false
  private current: QueueEntry | null = null

  get length(): number {
    return this.queue.length
  }

  get isProcessing(): boolean {
    return this.processing
  }

  get currentTask(): MRTask | null {
    return this.current?.task ?? null
  }

  enqueue(task: MRTask): Promise<void> {
    return new Promise((resolve, reject) => {
      this.queue.push({ task, resolve, reject })
      console.log(`[queue] enqueued MR !${task.mrIid} for ${task.projectSlug}, queue length: ${this.queue.length}`)
      this.processNext()
    })
  }

  private async processNext() {
    if (this.processing) return
    if (this.queue.length === 0) return

    this.processing = true
    const entry = this.queue.shift()!
    this.current = entry

    console.log(`[queue] processing MR !${entry.task.mrIid} for ${entry.task.projectSlug}`)
    try {
      await processMR(entry.task)
      entry.resolve()
    } catch (error) {
      console.error(`[queue] failed MR !${entry.task.mrIid}:`, error)
      entry.reject(error instanceof Error ? error : new Error(String(error)))
    } finally {
      this.current = null
      this.processing = false
      this.processNext()
    }
  }
}
