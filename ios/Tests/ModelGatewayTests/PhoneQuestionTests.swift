import XCTest
@testable import RoubaoModel

final class PhoneQuestionTests: XCTestCase {
    func testChoiceQuestionsHaveNoImplicitAnswerAndAcceptCustomText() throws {
        let data = Data(#"{"question":"请确认比价条件","questions_json":"[{\"id\":\"capacity\",\"prompt\":\"容量？\",\"options\":[{\"label\":\"256GB\"},{\"label\":\"512GB\"}]},{\"id\":\"condition\",\"prompt\":\"新旧？\"}]"}"#.utf8)
        let q = try PhoneQuestion.decode(data, taskPrompt: "compare phones")
        XCTAssertEqual(q.items.count, 2)
        XCTAssertThrowsError(try q.response([:]))
        XCTAssertThrowsError(try q.response(["capacity": "256GB"]))
        let answer = try q.response(["capacity": "1TB", "condition": "国行全新，第三方店也可以"])
        XCTAssertTrue(answer.contains("1TB")); XCTAssertTrue(answer.contains("国行全新"))
    }
    func testLegacyQuestionAndInvalidStructuredQuestions() throws {
        let q = try PhoneQuestion.decode(Data(#"{"question":"请选择容量"}"#.utf8), taskPrompt: "test")
        XCTAssertEqual(q.items.first?.id, "answer")
        XCTAssertEqual(q.items.first?.prompt, "请选择容量")
        for bad in [#"{"question":" "}"#, #"{"question":"test","questions":[]}"#,
                    #"{"question":"test","questions":[{"id":"a","prompt":"one"},{"id":"a","prompt":"two"}]}"#,
                    #"{"question":"test","questions":[{"id":"a","prompt":"one","options":[{"label":"same"},{"label":"same"}]}]}"#] {
            XCTAssertThrowsError(try PhoneQuestion.decode(Data(bad.utf8), taskPrompt: "test"))
        }
    }
    func testPendingQuestionAndSubmittedAnswerSurviveReopening() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = PhoneQuestionStore(url: folder.appendingPathComponent("pending.json"))
        let question = PhoneQuestion.text("需要哪种容量？", taskPrompt: "compare phones")
        try store.save(.init(question: question))
        let reopened = PhoneQuestionStore(url: store.url)
        XCTAssertEqual(reopened.load()?.question.id, question.id)
        XCTAssertNil(reopened.load()?.answer)
        try reopened.save(.init(question: question, draftAnswers: ["answer": "512GB"], draftStep: 1))
        XCTAssertEqual(store.load()?.draftAnswers?["answer"], "512GB")
        XCTAssertNil(store.load()?.answer, "Draft choices are not a submitted answer")
        try reopened.save(.init(question: question, answer: "512GB"))
        XCTAssertEqual(store.load()?.answer, "512GB")
        store.clear(); XCTAssertNil(reopened.load())
    }
    func testQuestionWaitDoesNotCallModelAgainBeforeAnswer() async throws {
        let (config, session, folder, calls, original) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        var callbacks = original
        let asked = expectation(description: "question displayed")
        callbacks.execute = { name, _, _ in
            XCTAssertEqual(name, "ask_user_question")
            asked.fulfill()
            try await Task.sleep(for: .milliseconds(150))
            return "owner-selected-512GB"
        }
        MockTransport.scripted = [PhoneHarnessTests().response("ask_user_question", args: #"{"question":"容量？"}"#), PhoneHarnessTests().response("done", args: #"{"summary":"answer received"}"#)]
        let task = Task { try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session) }
        await fulfillment(of: [asked], timeout: 2)
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 0)
        XCTAssertEqual(MockTransport.requests.count, 1)
        let result = try await task.value; XCTAssertEqual(result, "done: answer received")
        XCTAssertEqual(MockTransport.requests.count, 2)
    }
    func testCancellationWhileWaitingCannotExecuteNextAction() async throws {
        let (config, session, folder, calls, original) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: folder) }
        var callbacks = original
        let asked = expectation(description: "waiting for owner")
        callbacks.execute = { _, _, _ in asked.fulfill(); try await Task.sleep(for: .seconds(30)); return "not an answer" }
        MockTransport.scripted = [PhoneHarnessTests().response("ask_user_question", args: #"{"question":"容量？"}"#)]
        let task = Task { try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: folder, session: session) }
        await fulfillment(of: [asked], timeout: 2)
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") } catch {}
        let counts = await calls.counts(); XCTAssertEqual(counts.1, 0)
        XCTAssertEqual(MockTransport.requests.count, 1)
    }
}

extension PhoneQuestionTests {
    func testSubmittedCorrectionResumesOriginalQuestionDespiteDifferentLatestTask() async throws {
        let (config, session, root, _, original) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        var callbacks = original
        let asked = expectation(description: "question awaiting owner before process interruption")
        callbacks.execute = { name, _, _ in
            if name == "ask_user_question" { asked.fulfill(); try await Task.sleep(for: .seconds(30)) }
            return "ok"
        }
        MockTransport.scripted = [PhoneHarnessTests().response("ask_user_question", args: #"{"question":"文件已发送，需要补充还是撤回？"}"#)]
        let interrupted = Task {
            try await PhoneHarness.run(prompt: "把原版报告发给微信的测试联系人", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        }
        await fulfillment(of: [asked], timeout: 2)
        interrupted.cancel()
        do { _ = try await interrupted.value; XCTFail("Expected interrupted wait") } catch {}
        let originalTask = try PhoneHarness.savedTask(at: root)
        MockTransport.scripted = [PhoneHarnessTests().response("done", args: #"{"summary":"UNRELATED_CHAT_SENTINEL"}"#)]
        _ = try await PhoneHarness.run(prompt: "不相关任务", config: config, callbacks: original, checkpointDirectory: root, session: session)
        var question = PhoneQuestion.text("文件已发送，需要补充还是撤回？", taskPrompt: "把原版报告发给微信的测试联系人")
        question.conversationID = UUID(); question.taskFolder = originalTask.folder
        let store = PhoneQuestionStore(url: root.appendingPathComponent("pending-owner-answer.json"))
        try store.save(.init(question: question, answer: "你还没发送呢"))
        let persisted = try XCTUnwrap(store.load())
        XCTAssertEqual(persisted.question.taskFolder, originalTask.folder)
        MockTransport.scripted = [PhoneHarnessTests().response("done", args: #"{"summary":"已核对用户纠正，继续同一任务"}"#)]
        _ = try await PhoneHarness.run(prompt: originalTask.prompt, config: config, callbacks: original, checkpointDirectory: root, resume: true, session: session, resumeFolder: originalTask.folder, answeredQuestion: persisted)
        let body = try PhoneConversationTests().requestBody()
        XCTAssertTrue(body.contains("把原版报告发给微信的测试联系人"))
        XCTAssertTrue(body.contains("你还没发送呢"))
        XCTAssertFalse(body.contains("UNRELATED_CHAT_SENTINEL"))
        XCTAssertEqual(try PhoneHarness.savedTask(at: root).folder, originalTask.folder)
    }
    func testQuestionFromAnotherFolderCannotBeAppliedToTask() async throws {
        let (config, session, root, _, callbacks) = PhoneHarnessTests().setup()
        defer { try? FileManager.default.removeItem(at: root) }
        MockTransport.scripted = [PhoneHarnessTests().response("done", args: #"{"summary":"done"}"#)]
        _ = try await PhoneHarness.run(prompt: "test", config: config, callbacks: callbacks, checkpointDirectory: root, session: session)
        let saved = try PhoneHarness.savedTask(at: root)
        var question = PhoneQuestion.text("old?", taskPrompt: "other")
        question.taskFolder = UUID().uuidString
        do {
            _ = try await PhoneHarness.run(prompt: saved.prompt, config: config, callbacks: callbacks, checkpointDirectory: root, resume: true, session: session, resumeFolder: saved.folder, answeredQuestion: .init(question: question, answer: "answer"))
            XCTFail("Must refuse mismatched owner answer")
        } catch PhoneHarnessError.checkpointMismatch {}
    }
}
