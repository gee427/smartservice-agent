"""
Day 3: RAG 知识库
目标：让 Agent 能回答产品文档中的专业问题
"""
from langchain_community.document_loaders import TextLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import Chroma
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langgraph.prebuilt import create_react_agent
from langchain_core.messages import HumanMessage
import os
import os
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"


# ========== 1. 创建产品手册 ==========
product_manual = """
产品名称：SmartWatch Pro X1

【基本参数】
- 屏幕：1.5英寸 AMOLED，分辨率466x466，支持AOD息屏显示
- 电池：450mAh，典型使用7天，重度使用3天，待机30天
- 防水：5ATM，支持游泳和淋浴
- 传感器：心率、血氧、GPS、气压计、加速度计、陀螺仪
- 连接：蓝牙5.2、NFC、Wi-Fi
- 系统：支持Android 8.0+和iOS 13+

【充电说明】
- 使用磁吸式充电底座，充满约2小时
- 支持快充：充电10分钟可使用1天
- 请务必使用原装充电器，第三方充电器可能导致电池损坏或发热
- 充电时手表会显示充电动画，充满后自动停止

【常见问题】
Q: 手表充不进电怎么办？
A: 1. 检查充电触点是否有污垢，用干布擦拭
   2. 更换充电线测试，排除线材问题
   3. 长按电源键15秒强制重启手表
   4. 如果以上无效，请联系售后

Q: 心率监测不准确？
A: 1. 确保手表佩戴紧贴手腕，不要太松
   2. 运动时请开启对应的运动模式
   3. 在App中重新校准心率传感器
   4. 保持手腕清洁干燥

Q: 如何恢复出厂设置？
A: 设置 → 系统 → 重置 → 确认重置。注意：所有数据将清空且无法恢复，请提前备份。

Q: 手表可以戴着游泳吗？
A: 可以。SmartWatch Pro X1 支持5ATM防水，可在50米深水中使用，支持泳池游泳和开放水域游泳模式。

【保修政策】
- 整机保修1年，电池保修6个月
- 人为损坏（进水超深度、摔落、拆解）不在保修范围
- 保修需出示购买凭证和保修卡
- 官方售后电话：400-888-9999
"""

manual_path = os.path.join(os.path.dirname(__file__), "product_manual.txt")
with open(manual_path, "w", encoding="utf-8") as f:
    f.write(product_manual)
print("产品手册已生成")

# ========== 2. 加载和切分文档 ==========
loader = TextLoader(manual_path, encoding="utf-8")
documents = loader.load()

text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=300,
    chunk_overlap=50,
    separators=["\n\n", "\n", "。", "，", " ", ""]
)
texts = text_splitter.split_documents(documents)
print(f"文档切分为 {len(texts)} 个片段")

# ========== 3. 创建向量数据库 ==========
embeddings = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={"device": "cpu"}
)

db_path = os.path.join(os.path.dirname(__file__), "chroma_db")
vectorstore = Chroma.from_documents(
    documents=texts,
    embedding=embeddings,
    persist_directory=db_path
)

retriever = vectorstore.as_retriever(search_kwargs={"k": 2})

# ========== 4. LLM + Agent ==========
llm = ChatOpenAI(
    model="local-model",
    api_key="not-needed",
    base_url="http://localhost:1234/v1",
    temperature=0.7
)

@tool
def search_product_kb(query: str) -> str:
    """查询SmartWatch Pro X1产品知识库，回答产品使用、故障排查、保修等问题"""
    docs = retriever.invoke(query)
    if not docs:
        return "未找到相关信息"
    return "\n\n".join([f"[相关文档] {doc.page_content}" for doc in docs])

@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气"""
    return "晴，25°C"

tools = [search_product_kb, get_weather]

agent = create_react_agent(model=llm, tools=tools)


# ========== 流式运行 ==========
def run_agent(query: str, label: str):
    print("\n" + "=" * 60)
    print(f"【{label}】{query}")
    print("-" * 60)

    final_answer = ""
    for event in agent.stream({"messages": [HumanMessage(content=query)]}):
        for node_name, output in event.items():
            if node_name == "agent":
                msg = output["messages"][-1]
                if msg.tool_calls:
                    for tc in msg.tool_calls:
                        print(f"  🤔 调用 {tc['name']}({tc['args']})")
                elif msg.content:
                    final_answer = msg.content
            elif node_name == "tools":
                for msg in output["messages"]:
                    if hasattr(msg, "content") and msg.content:
                        content_preview = msg.content[:200]
                        print(f"  🔧 结果：{content_preview}")

    print(f"\n【最终结果】{final_answer}")


# ========== 测试 ==========
if __name__ == "__main__":
    print("\n=== RAG Agent 测试 ===\n")

    run_agent("我的手表充不进电怎么办？", "测试1 产品知识问答")

    run_agent("手表保修多久？电池坏了能保修吗？", "测试2 保修政策")

    run_agent("这个手表能戴着游泳吗？防水怎么样？", "测试3 功能咨询")
